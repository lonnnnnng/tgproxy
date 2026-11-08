package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"tgproxy/internal/config"
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
	if *mediaDir == "" {
		*mediaDir = filepath.Join(*dataDir, "media")
	}
	base := *strmBase
	if base == "" {
		base = "http://" + lanIP() + ":8080"
		if p := strings.LastIndex(*listen, ":"); p >= 0 && p != len(*listen)-1 {
			base = "http://" + lanIP() + (*listen)[p:]
		}
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

	// tgClient is created (or recreated) whenever credentials change.
	var tgMu sync.Mutex
	var tgClient *telegram.Client
	startClient := func(appID int, appHash string) {
		tgMu.Lock()
		defer tgMu.Unlock()
		tgClient = telegram.New(appID, appHash)
	}
	connectClient := func(sess []byte) {
		go func() {
			runCtx, runCancel := context.WithCancel(rootCtx)
			defer runCancel()
			tgMu.Lock()
			c := tgClient
			tgMu.Unlock()
			if c == nil {
				return
			}
			if err := c.Connect(runCtx, sess, nil); err != nil && !errors.Is(err, context.Canceled) {
				log.Printf("telegram connect: %v", err)
			}
		}()
	}
	if cfg.AppID != 0 && cfg.AppHash != "" {
		startClient(cfg.AppID, cfg.AppHash)
	}

	// Resolve for streaming: look up item, then fetch file handle from telegram.
	src := keySource{resolve: func(file string) (stream.ChunkSource, int64, string, error) {
		chID, msgID, err := parseStreamKey(file)
		if err != nil {
			return nil, 0, "", err
		}
		tgMu.Lock()
		c := tgClient
		tgMu.Unlock()
		if c == nil {
			return nil, 0, "", errors.New("telegram not configured")
		}
		it, ok := lib.FindItem(chID, msgID)
		if !ok {
			return nil, 0, "", errors.New("unknown file; scan channels first")
		}
		ctx, cancel := context.WithTimeout(rootCtx, 60*time.Second)
		defer cancel()
		h, err := c.ChunkFile(ctx, chID, msgID)
		if err != nil {
			return nil, 0, "", err
		}
		cs := c.ChunkSource(h, cfg.ChunkSize)
		ctype := mimeOf(it)
		return cs, h.Size, ctype, nil
	}}

	// Login flow: the telegram client keeps a persistent login session so the
	// code's phone_code_hash survives between "send code" and "sign in".
	currentTG := func() *telegram.Client {
		tgMu.Lock()
		defer tgMu.Unlock()
		return tgClient
	}
	var loginMu chan struct{} = make(chan struct{}, 1)
	withLoginClient := func(fn func(c *telegram.Client) error) error {
		select {
		case loginMu <- struct{}{}:
			defer func() { <-loginMu }()
		default:
			return errors.New("another login step is in progress")
		}
		c := currentTG()
		if c == nil {
			return errors.New("api credentials not configured; set them first")
		}
		return fn(c)
	}
	sendCode := func(ctx context.Context, phone string) error {
		return withLoginClient(func(c *telegram.Client) error {
			return c.SendCode(ctx, phone)
		})
	}
	signIn := func(ctx context.Context, code string) error {
		return withLoginClient(func(c *telegram.Client) error {
			return c.SignIn(ctx, code)
		})
	}
	submitPassword := func(ctx context.Context, password string) error {
		return withLoginClient(func(c *telegram.Client) error {
			return c.SubmitPassword(ctx, password)
		})
	}
	onLoginSuccess := func() []byte {
		c := currentTG()
		if c == nil {
			return nil
		}
		sess := c.Session()
		if len(sess) == 0 {
			return nil
		}
		cfg.Session = sess
		if err := config.Save(*dataDir, cfg); err != nil {
			log.Printf("save session: %v", err)
			return nil
		}
		// Restart long-running client with the new session.
		connectClient(sess)
		return sess
	}

	saveCredentials := func(ctx context.Context, appID int, appHash string) error {
		cfg.AppID, cfg.AppHash = appID, appHash
		if err := config.Save(*dataDir, cfg); err != nil {
			return err
		}
		startClient(appID, appHash)
		if len(cfg.Session) > 0 {
			connectClient(cfg.Session)
		}
		return nil
	}
	hasCredentials := func() bool {
		return currentTG() != nil
	}

	mux := http.NewServeMux()
	mux.Handle("/stream/", http.StripPrefix("/stream/", cache.Handler(src)))
	mux.Handle("/", web.NewServer(web.Deps{
		Store:           lib,
		TGFn:            currentTG,
		StrmBase:        base,
		MediaDir:        *mediaDir,
		SendCode:        sendCode,
		SignIn:          signIn,
		SubmitPassword:  submitPassword,
		OnLoginSuccess:  onLoginSuccess,
		SaveCredentials: saveCredentials,
		HasCredentials:  hasCredentials,
	}).Handler())

	srv := &http.Server{Addr: *listen, Handler: mux}
	go func() {
		log.Printf("tgproxy listening on %s (strm base %s, media %s)", *listen, base, *mediaDir)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("http: %v", err)
		}
	}()

	// Restore session on boot: connect if we have one.
	if cfg.AppID != 0 && cfg.AppHash != "" && len(cfg.Session) > 0 {
		connectClient(cfg.Session)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Println("shutting down")
	cancel()
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
	if err != nil {
		return 0, 0, errors.New("bad message id in stream key")
	}
	return ch, msg, nil
}

// keySource adapts a resolve func to stream.Source.
type keySource struct {
	resolve func(file string) (stream.ChunkSource, int64, string, error)
}

func (k keySource) Resolve(file string) (stream.ChunkSource, int64, string, error) {
	return k.resolve(file)
}

func mimeOf(it store.Item) string {
	if it.MIME != "" {
		return it.MIME
	}
	return "video/mp4"
}
