package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"tgproxy/internal/config"
	"tgproxy/internal/jellyfin"
	"tgproxy/internal/store"
	"tgproxy/internal/stream"
	"tgproxy/internal/telegram"
	"tgproxy/internal/web"
)

func main() {
	dataDir := flag.String("data", defaultDataDir(), "data directory (session, library.json)")
	listen := flag.String("listen", ":8080", "listen address")
	mediaDir := flag.String("media", "", "media tree root (default <data>/media)")
	strmBase := flag.String("strm-base", "", "base URL written into .strm files (default http://<lan-ip>:8080)")
	flag.Parse()

	cfg, err := config.Load(*dataDir)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	explicit := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { explicit[f.Name] = true })
	if !explicit["listen"] {
		*listen = cfg.Listen
	}
	if !explicit["media"] {
		*mediaDir = cfg.MediaRoot
	}
	if !explicit["strm-base"] {
		*strmBase = cfg.StrmBase
	}
	if *mediaDir == "" {
		*mediaDir = filepath.Join(*dataDir, "media")
	}
	cfg.Listen, cfg.MediaRoot, cfg.StrmBase = *listen, *mediaDir, *strmBase
	if err := cfg.Validate(); err != nil {
		log.Fatal(err)
	}
	base := strings.TrimRight(*strmBase, "/")
	if base == "" {
		_, port, _ := net.SplitHostPort(*listen)
		base = "http://" + net.JoinHostPort(lanIP(), port)
	}

	if err := os.MkdirAll(*mediaDir, 0o755); err != nil {
		log.Fatalf("mkdir media: %v", err)
	}

	lib, err := store.Open(*dataDir)
	if err != nil {
		log.Fatalf("open library: %v", err)
	}

	cache := stream.NewCache(nil, cfg.ChunkSize, cfg.CacheMaxBytes, cfg.PrefetchChunks)

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	defer cache.Close()
	app := &application{cfg: cfg, dataDir: *dataDir, ctx: rootCtx}
	app.client = telegram.New(cfg.AppID, cfg.AppHash, *dataDir)
	rawSource := keySource{resolve: func(ctx context.Context, file string) (stream.ChunkSource, int64, string, error) {
		ch, msg, err := parseStreamKey(file)
		if err != nil {
			return nil, 0, "", stream.ErrNotFound
		}
		c := app.currentTG()
		if c == nil || !c.Connected() {
			return nil, 0, "", errors.New("Telegram 未连接")
		}
		h, err := c.ChunkFile(ctx, ch, msg)
		if err != nil {
			return nil, 0, "", err
		}
		it, ok := lib.FindItem(ch, msg)
		if !ok {
			return nil, 0, "", stream.ErrNotFound
		}
		if it.MIME == "" {
			it.Name = h.Name
		}
		return c.ChunkSource(h, cfg.ChunkSize), h.Size, mimeOf(it), nil
	}}
	resolved := stream.NewCachedSource(rawSource, time.Minute, 128)
	app.resolved = resolved
	defer resolved.Close()
	// long: 每次请求仍核对索引，删除频道后即刻停用旧入口；仅复用 Telegram 文件句柄。
	src := keySource{invalidate: func(file string) {
		ch, msg, err := parseStreamKey(file)
		if err == nil {
			resolved.Invalidate(fmt.Sprintf("%d_%d", ch, msg))
		}
	}, resolve: func(ctx context.Context, file string) (stream.ChunkSource, int64, string, error) {
		ch, msg, err := parseStreamKey(file)
		if err != nil {
			return nil, 0, "", stream.ErrNotFound
		}
		if _, ok := lib.FindItem(ch, msg); !ok {
			return nil, 0, "", stream.ErrNotFound
		}
		return resolved.Resolve(ctx, fmt.Sprintf("%d_%d", ch, msg))
	}}
	var notify func(context.Context) error
	if cfg.JellyfinURL != "" {
		notify = func(ctx context.Context) error { return jellyfin.Refresh(ctx, cfg.JellyfinURL, cfg.JellyfinAPIKey) }
	}

	mux := http.NewServeMux()
	mux.Handle("/stream/", http.StripPrefix("/stream/", cache.Handler(src)))
	mux.Handle("/", web.NewServer(web.Deps{
		Store:           lib,
		TGFn:            app.currentTG,
		Context:         rootCtx,
		NotifyJellyfin:  notify,
		StrmBase:        base,
		MediaDir:        *mediaDir,
		SendCode:        app.sendCode,
		SignIn:          app.signIn,
		SubmitPassword:  app.submitPassword,
		SaveCredentials: app.saveCredentials,
		HasCredentials:  func() bool { return app.currentTG() != nil },
	}).Handler())

	srv := &http.Server{Addr: *listen, Handler: mux, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 32 << 10}
	go func() {
		log.Printf("tgproxy listening on %s (strm base %s, media %s)", *listen, base, *mediaDir)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("http: %v", err)
		}
	}()

	// Restore session on boot: connect if we have one.
	if cfg.AppID != 0 && cfg.AppHash != "" && len(cfg.Session) > 0 {
		app.mu.Lock()
		app.connectLocked(cfg.Session)
		app.mu.Unlock()
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Println("shutting down")
	cancel()
	app.close()
	shCtx, shCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shCancel()
	srv.Shutdown(shCtx)
}

func defaultDataDir() string {
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, ".tgproxy")
	}
	return ".tgproxy"
}

func lanIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() && ipnet.IP.To4() != nil {
			ip := ipnet.IP.String()
			if strings.HasPrefix(ip, "192.168.") || strings.HasPrefix(ip, "10.") || strings.HasPrefix(ip, "172.") {
				return ip
			}
		}
	}
	return "127.0.0.1"
}

func parseStreamKey(file string) (int64, int, error) {
	// format: <channel>_<msg>[/anything]
	s := file
	if i := strings.IndexByte(s, '/'); i >= 0 {
		s = s[:i]
	}
	parts := strings.SplitN(s, "_", 2)
	if len(parts) != 2 {
		return 0, 0, errors.New("bad stream key, want <channel>_<message>")
	}
	ch, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, 0, errors.New("bad channel id in stream key")
	}
	msg, err := strconv.Atoi(parts[1])
	if err != nil || msg <= 0 {
		return 0, 0, errors.New("bad message id in stream key")
	}
	return ch, msg, nil
}

// keySource adapts a resolve func to stream.Source.
type keySource struct {
	invalidate func(string)
	resolve    func(context.Context, string) (stream.ChunkSource, int64, string, error)
}

func (k keySource) Resolve(ctx context.Context, file string) (stream.ChunkSource, int64, string, error) {
	return k.resolve(ctx, file)
}

func mimeOf(it store.Item) string {
	if it.MIME != "" {
		return it.MIME
	}
	if typ := mime.TypeByExtension(strings.ToLower(filepath.Ext(it.Name))); typ != "" {
		return typ
	}
	return "application/octet-stream"
}

func (k keySource) Invalidate(file string) {
	if k.invalidate != nil {
		k.invalidate(file)
	}
}
