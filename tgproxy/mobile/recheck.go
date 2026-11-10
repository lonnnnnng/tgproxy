package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"tgproxy/internal/telegram"
	"time"
)

func mobileVideo(it telegram.MediaItem) video {
	return video{Key: fmt.Sprintf("%d_%d", it.ChannelID, it.MessageID), ChannelID: it.ChannelID, MessageID: it.MessageID, Name: it.Name, Size: it.Size, MIME: it.MIME, Date: it.Date, DocumentID: it.DocumentID}
}

func (e *Engine) RefreshChannel(channelID int64) (string, error) {
	if channelID <= 0 {
		return "", errors.New("频道参数无效")
	}
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	e.mu.Lock()
	if e.closed || e.scanCancelled {
		e.mu.Unlock()
		return "", safeError(context.Canceled)
	}
	e.scanCancel = cancel
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.scanCancel = nil; e.mu.Unlock() }()
	channel, err := e.client.RefreshChannel(ctx, channelID)
	if err != nil {
		return "", safeError(err)
	}
	return marshal(channel), nil
}

// long: 只回查本机已有的消息，错误不返回删除集合，Android 每批事务提交后才能继续。
func (e *Engine) RecheckMessages(channelID int64, idsJSON string) (string, error) {
	var ids []int
	if channelID <= 0 || len(idsJSON) > 4096 || json.Unmarshal([]byte(idsJSON), &ids) != nil || len(ids) == 0 || len(ids) > 100 {
		return "", errors.New("校验参数无效")
	}
	if !e.op.TryLock() {
		return "", errors.New("请等待当前操作结束")
	}
	defer e.op.Unlock()
	ctx, cancel := context.WithTimeout(e.ctx, 60*time.Second)
	defer cancel()
	e.mu.Lock()
	if e.closed || e.scanCancelled {
		e.mu.Unlock()
		return "", safeError(context.Canceled)
	}
	e.scanCancel = cancel
	e.mu.Unlock()
	defer func() { e.mu.Lock(); e.scanCancel = nil; e.mu.Unlock() }()
	result, err := e.client.RecheckMessages(ctx, channelID, ids)
	if err != nil {
		return "", safeError(err)
	}
	rows := make([]video, 0, len(result.Items))
	for _, item := range result.Items {
		v := mobileVideo(item)
		rows = append(rows, v)
		e.resolved.Invalidate(v.Key)
	}
	for _, id := range result.Missing {
		key := fmt.Sprintf("%d_%d", channelID, id)
		e.mu.Lock()
		delete(e.items, key)
		e.mu.Unlock()
		e.resolved.Invalidate(key)
	}
	return marshal(map[string]any{"items": rows, "missing_ids": result.Missing, "title": result.Title}), nil
}
