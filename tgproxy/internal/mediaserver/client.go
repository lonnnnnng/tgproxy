package mediaserver

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
)

func Refresh(ctx context.Context, base, key string) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	r, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(base, "/")+"/Library/Refresh", nil)
	if err != nil {
		return err
	}
	r.Header.Set("X-Emby-Token", key)
	// long: 拒绝跳转到其他主机，避免把媒体服务器密钥发送给重定向目标。
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Do(r)
	if err != nil {
		return fmt.Errorf("Emby 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Emby 刷新返回 HTTP %d", resp.StatusCode)
	}
	return nil
}

// RefreshLibrary refreshes only the Emby library containing path.
func RefreshLibrary(ctx context.Context, base, key, path string) error {
	libs, err := ListLibraries(ctx, base, key)
	if err != nil {
		return err
	}
	for _, lib := range libs {
		for _, location := range lib.Locations {
			if strings.TrimRight(location, "/") != strings.TrimRight(path, "/") {
				continue
			}
			query := url.Values{
				"Recursive":           {"true"},
				"MetadataRefreshMode": {"Default"},
				"ImageRefreshMode":    {"Default"},
				"ReplaceAllMetadata":  {"false"},
				"ReplaceAllImages":    {"false"},
			}
			resp, err := request(ctx, http.MethodPost, base, key, "/Items/"+url.PathEscape(lib.ItemID)+"/Refresh", query)
			if err != nil {
				return fmt.Errorf("Emby 连接失败")
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
				return fmt.Errorf("Emby 媒体库 %q 刷新返回 HTTP %d", lib.Name, resp.StatusCode)
			}
			return nil
		}
	}
	return fmt.Errorf("Emby 未找到路径为 %q 的媒体库", path)
}

// Library is one Emby virtual folder.
type Library struct {
	Name           string         `json:"Name"`
	ItemID         string         `json:"-"`
	Locations      []string       `json:"Locations"`
	LibraryOptions map[string]any `json:"LibraryOptions"`
}

type ImageTags struct {
	Primary string `json:"Primary"`
}

type LibraryItem struct {
	Path      string    `json:"Path"`
	ImageTags ImageTags `json:"ImageTags"`
}

type libraryItemsResponse struct {
	Items []LibraryItem `json:"Items"`
}

// SeriesWithPosters returns Series paths that currently have a primary image.
func SeriesWithPosters(ctx context.Context, base, key, libraryPath string) (map[string]bool, error) {
	libs, err := ListLibraries(ctx, base, key)
	if err != nil {
		return nil, err
	}
	var itemID string
	for _, lib := range libs {
		for _, loc := range lib.Locations {
			if strings.TrimRight(loc, "/") == strings.TrimRight(libraryPath, "/") {
				itemID = lib.ItemID
				break
			}
		}
	}
	if itemID == "" {
		return nil, fmt.Errorf("Emby 未找到路径为 %q 的媒体库", libraryPath)
	}
	query := url.Values{
		"ParentId":         {itemID},
		"Recursive":        {"true"},
		"IncludeItemTypes": {"Series"},
		"Fields":           {"Path,ImageTags"},
		"Limit":            {"10000"},
	}
	resp, err := request(ctx, http.MethodGet, base, key, "/Items", query)
	if err != nil {
		return nil, fmt.Errorf("Emby 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Emby Series 查询返回 HTTP %d", resp.StatusCode)
	}
	var result libraryItemsResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("Emby Series 列表解析失败: %w", err)
	}
	withPoster := make(map[string]bool)
	for _, item := range result.Items {
		if item.Path != "" && item.ImageTags.Primary != "" {
			withPoster[filepath.Clean(item.Path)] = true
		}
	}
	return withPoster, nil
}

func (l *Library) UnmarshalJSON(data []byte) error {
	type libraryFields Library
	var value struct {
		*libraryFields
		ItemID json.RawMessage `json:"ItemId"`
	}
	value.libraryFields = (*libraryFields)(l)
	if err := json.Unmarshal(data, &value); err != nil {
		return err
	}
	if len(value.ItemID) == 0 || string(value.ItemID) == "null" {
		return nil
	}
	if value.ItemID[0] == '"' {
		return json.Unmarshal(value.ItemID, &l.ItemID)
	}
	var number json.Number
	if err := json.Unmarshal(value.ItemID, &number); err != nil {
		return fmt.Errorf("invalid Emby library item id: %w", err)
	}
	l.ItemID = number.String()
	return nil
}

type cancelBody struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (b *cancelBody) Close() error {
	err := b.ReadCloser.Close()
	b.cancel()
	return err
}

func request(ctx context.Context, method, base, key, path string, query url.Values) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	u := strings.TrimRight(base, "/") + path
	if query != nil {
		u += "?" + query.Encode()
	}
	r, err := http.NewRequestWithContext(ctx, method, u, nil)
	if err != nil {
		cancel()
		return nil, err
	}
	r.Header.Set("X-Emby-Token", key)
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Do(r)
	if err != nil {
		cancel()
		return nil, err
	}
	resp.Body = &cancelBody{ReadCloser: resp.Body, cancel: cancel}
	return resp, nil
}

// ListLibraries returns all Emby virtual folders, retrying transient failures.
func ListLibraries(ctx context.Context, base, key string) ([]Library, error) {
	var last error
	for attempt := 0; attempt < 3; attempt++ {
		libs, err := listLibrariesOnce(ctx, base, key)
		if err == nil {
			return libs, nil
		}
		last = err
		select {
		case <-ctx.Done():
			return nil, err
		case <-time.After(500 * time.Millisecond):
		}
	}
	return nil, last
}

func listLibrariesOnce(ctx context.Context, base, key string) ([]Library, error) {
	resp, err := request(ctx, http.MethodGet, base, key, "/Library/VirtualFolders", url.Values{})
	if err != nil {
		return nil, fmt.Errorf("Emby 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Emby 媒体库列表返回 HTTP %d", resp.StatusCode)
	}
	var libs []Library
	if err := json.NewDecoder(resp.Body).Decode(&libs); err != nil {
		return nil, fmt.Errorf("Emby 媒体库列表解析失败: %w", err)
	}
	return libs, nil
}

// EnsureLibrary creates a tvshows library for path when it does not exist.
func EnsureLibrary(ctx context.Context, base, key, name, path string) error {
	libs, err := ListLibraries(ctx, base, key)
	if err != nil {
		return err
	}
	for _, lib := range libs {
		for _, loc := range lib.Locations {
			if strings.TrimRight(loc, "/") == strings.TrimRight(path, "/") {
				return configureLibrary(ctx, base, key, lib.ItemID, path, libs)
			}
		}
	}
	q := url.Values{}
	q.Set("name", name)
	q.Set("collectionType", "tvshows")
	q.Set("paths", path)
	q.Set("refreshLibrary", "true")
	resp, err := request(ctx, http.MethodPost, base, key, "/Library/VirtualFolders", q)
	if err != nil {
		return fmt.Errorf("Emby 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Emby 创建媒体库返回 HTTP %d", resp.StatusCode)
	}
	libs, err = ListLibraries(ctx, base, key)
	if err != nil {
		// 库已创建，配置可留到下次启动/添加时再补。
		return nil
	}
	for _, lib := range libs {
		for _, loc := range lib.Locations {
			if strings.TrimRight(loc, "/") == strings.TrimRight(path, "/") {
				return configureLibrary(ctx, base, key, lib.ItemID, path, libs)
			}
		}
	}
	return nil
}

func configureLibrary(ctx context.Context, base, key, itemID, path string, libs []Library) error {
	var opts map[string]any
	for _, lib := range libs {
		if len(lib.LibraryOptions) > 0 {
			if types, ok := lib.LibraryOptions["TypeOptions"].([]any); ok && len(types) > 0 {
				opts = lib.LibraryOptions
				break
			}
		}
	}
	if opts == nil {
		opts = defaultLibraryOptions()
	} else {
		cp := make(map[string]any, len(opts))
		for k, v := range opts {
			cp[k] = v
		}
		opts = cp
	}
	opts["PathInfos"] = []any{map[string]any{"Path": path}}
	opts["ContentType"] = "tvshows"
	opts["PreferredMetadataLanguage"] = "zh-CN"
	opts["PreferredImageLanguage"] = "zh-CN"
	opts["MetadataCountryCode"] = "CN"
	body, err := json.Marshal(map[string]any{"Id": itemID, "LibraryOptions": opts})
	if err != nil {
		return err
	}
	resp, err := requestBody(ctx, http.MethodPost, base, key, "/Library/VirtualFolders/LibraryOptions", body)
	if err != nil {
		return fmt.Errorf("Emby 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Emby 更新媒体库配置返回 HTTP %d", resp.StatusCode)
	}
	return nil
}

func defaultLibraryOptions() map[string]any {
	series := map[string]any{
		"Type":                 "Series",
		"MetadataFetchers":     []any{"TheTVDB", "TheMovieDb"},
		"MetadataFetcherOrder": []any{},
		"ImageFetchers":        []any{"TheTVDB", "FanArt"},
		"ImageFetcherOrder":    []any{},
		"ImageOptions":         []any{},
	}
	season := map[string]any{
		"Type": "Season", "MetadataFetchers": []any{"TheTVDB"}, "MetadataFetcherOrder": []any{},
		"ImageFetchers": []any{"TheTVDB"}, "ImageFetcherOrder": []any{}, "ImageOptions": []any{},
	}
	episode := map[string]any{
		"Type": "Episode", "MetadataFetchers": []any{"TheTVDB"}, "MetadataFetcherOrder": []any{},
		"ImageFetchers": []any{"TheTVDB", "Image Capture"}, "ImageFetcherOrder": []any{}, "ImageOptions": []any{},
	}
	return map[string]any{
		"PathInfos":                 []any{},
		"ContentType":               "tvshows",
		"PreferredMetadataLanguage": "zh-CN",
		"PreferredImageLanguage":    "zh-CN",
		"MetadataCountryCode":       "CN",
		"TypeOptions":               []any{series, season, episode},
	}
}

func requestBody(ctx context.Context, method, base, key, path string, body []byte) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	u := strings.TrimRight(base, "/") + path
	r, err := http.NewRequestWithContext(ctx, method, u, strings.NewReader(string(body)))
	if err != nil {
		cancel()
		return nil, err
	}
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Emby-Token", key)
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Do(r)
	if err != nil {
		cancel()
		return nil, err
	}
	resp.Body = &cancelBody{ReadCloser: resp.Body, cancel: cancel}
	return resp, nil
}

// DeleteLibraryByPath removes the library whose location matches path.
func DeleteLibraryByPath(ctx context.Context, base, key, path string) error {
	libs, err := ListLibraries(ctx, base, key)
	if err != nil {
		return err
	}
	for _, lib := range libs {
		for _, loc := range lib.Locations {
			if strings.TrimRight(loc, "/") != strings.TrimRight(path, "/") {
				continue
			}
			body, err := json.Marshal(map[string]any{"Id": lib.ItemID, "RefreshLibrary": false})
			if err != nil {
				return err
			}
			resp, err := requestBody(ctx, http.MethodPost, base, key, "/Library/VirtualFolders/Delete", body)
			if err != nil {
				return fmt.Errorf("Emby 连接失败")
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
				return fmt.Errorf("Emby 删除媒体库返回 HTTP %d", resp.StatusCode)
			}
			return nil
		}
	}
	return nil
}
