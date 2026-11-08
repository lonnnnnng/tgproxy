package jellyfin

import (
	"context"
	"fmt"
	"net/http"
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
	r.Header.Set("Authorization", `MediaBrowser Client="tgproxy", Device="Server", DeviceId="tgproxy", Version="1", Token="`+key+`"`)
	// long: 拒绝跳转到其他主机，避免把媒体服务器密钥发送给重定向目标。
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Do(r)
	if err != nil {
		return fmt.Errorf("Jellyfin 连接失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("Jellyfin 刷新返回 HTTP %d", resp.StatusCode)
	}
	return nil
}
