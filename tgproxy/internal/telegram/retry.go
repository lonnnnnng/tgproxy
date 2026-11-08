package telegram

import (
	"context"
	"github.com/gotd/td/bin"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"time"
)

func floodMiddleware(next tg.Invoker) telegram.InvokeFunc {
	return func(ctx context.Context, in bin.Encoder, out bin.Decoder) error {
		var err error
		for attempt := 0; attempt < 6; attempt++ {
			err = next.Invoke(ctx, in, out)
			wait, ok := tgerr.AsFloodWait(err)
			if !ok || wait < 0 || wait > 30*time.Minute || attempt == 5 {
				return err
			}
			// long: 完整历史扫描遵守服务端限流时间；取消或超时会立即结束等待。
			timer := time.NewTimer(wait + time.Millisecond)
			select {
			case <-ctx.Done():
				timer.Stop()
				return ctx.Err()
			case <-timer.C:
			}
		}
		return err
	}
}
