package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ListChannels long: 只返回频道展示信息，access_hash 和会话始终留在内核。
func (e *Engine) ListChannels() (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 90*time.Second)
	defer cancel()
	rows, err := e.client.JoinedChannels(ctx)
	if err != nil {
		return "", safeError(err)
	}
	return marshal(rows), nil
}

func (e *Engine) FindChannel(ref string) (string, error) {
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	id, title, err := e.client.ResolveChannel(ctx, strings.TrimSpace(ref))
	if err != nil {
		return "", safeError(err)
	}
	return marshal(map[string]any{"channel_id": id, "title": title, "ref": strings.TrimSpace(ref)}), nil
}

// ScanPage long: 一次只交付完整原始页，由 SQLite 将视频和游标一并提交后再请求下一页。
func (e *Engine) ScanPage(channelID, offsetID, minID int64) (string, error) {
	if channelID <= 0 || offsetID < 0 || offsetID > 2147483647 || minID < 0 || minID > 2147483647 {
		return "", errors.New("频道扫描参数无效")
	}
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 60*time.Second)
	defer cancel()
	e.mu.Lock()
	e.scanCancel = cancel
	e.scanning = true
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.scanCancel = nil; e.scanning = false; e.mu.Unlock() }()
	page, err := e.client.ChannelHistoryPage(ctx, channelID, int(offsetID), int(minID))
	if err != nil {
		return "", safeError(err)
	}
	rows := make([]video, 0, len(page.Items))
	for _, it := range page.Items {
		rows = append(rows, video{fmt.Sprintf("%d_%d", it.ChannelID, it.MessageID), it.ChannelID, it.MessageID, it.Name, it.Size, it.MIME, it.Date})
	}
	return marshal(map[string]any{"items": rows, "next_id": page.NextID, "newest_id": page.NewestID, "done": page.Done, "messages": page.Messages}), nil
}

// RegisterVideo long: 按播放需要装入标识，数据库规模不再受启动快照的 1000 条限制。
func (e *Engine) RegisterVideo(payload string) error {
	var v video
	if len(payload) > 64<<10 || json.Unmarshal([]byte(payload), &v) != nil || v.ChannelID <= 0 || v.MessageID <= 0 || v.Size < 0 || v.Key != fmt.Sprintf("%d_%d", v.ChannelID, v.MessageID) {
		return errors.New("媒体索引无效")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return errors.New("播放服务已关闭")
	}
	e.items[v.Key] = v
	return nil
}
