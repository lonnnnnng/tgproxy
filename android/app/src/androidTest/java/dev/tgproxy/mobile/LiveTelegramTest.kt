package dev.tgproxy.mobile

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTelegramTest {
    @After fun stopTestPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { PlaybackService.stopPlayback(instrumentation.targetContext) }
    }
    @Test fun foregroundIndexCanPauseAndResume() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegram") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as TgApplication
        fun await(seconds: Int, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (!condition()) { if (System.currentTimeMillis() >= deadline) fail("scan service timed out"); Thread.sleep(100) }
        }
        await(60) { app.engine?.let { JSONObject(it.status()).optBoolean("connected") } == true }
        val channel = app.database.channel() ?: error("需要已有频道")
        val id = channel.getLong("channel_id")
        assumeTrue(!channel.optBoolean("history_done"))
        val initialCount = app.database.count(id)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(2) {
                val before = app.database.channel(id)!!.optLong("history_offset")
                scenario.onActivity { activity -> androidx.core.content.ContextCompat.startForegroundService(activity,
                    Intent(activity, ScanService::class.java).putExtra("channel", id).putExtra("refresh", false)) }
                try {
                    await(60) { val current=app.database.channel(id)!!; current.optLong("history_offset") != before || current.optBoolean("history_done") }
                } finally { instrumentation.runOnMainSync { app.cancelScan() } }
                await(65) { !app.busy && !app.scanning }
                val after=app.database.channel(id)!!.optLong("history_offset")
                assertTrue("历史游标未向旧消息推进", before == 0L || after < before || app.database.channel(id)!!.optBoolean("history_done"))
            }
        }
        assertTrue(app.database.count(id) >= initialCount)
        instrumentation.sendStatus(0,Bundle().apply { putString("stream", "scan_pause_resume=true indexed_before=$initialCount indexed_after=${app.database.count(id)}\n") })
    }

    @Test fun existingSessionChannelReadAndVideoSeek() {
        // long: 真实网络验收必须显式启用，只读取现有频道与索引，不新增频道或改写扫描断点。
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegram") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as TgApplication
        var lastState = "等待 Telegram 连接"
        fun await(seconds: Int, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (!condition()) { if (System.currentTimeMillis() >= deadline) fail("live check timed out: $lastState"); Thread.sleep(200) }
        }
        await(60) { app.engine?.let { JSONObject(it.status()).optBoolean("connected") } == true }
        val core = app.engine!!
        val channels = JSONArray(core.listChannels())
        assertTrue("当前账号没有可枚举的频道", channels.length() > 0)
        val channel = app.database.channel() ?: error("需要已有索引")
        val page = JSONObject(core.scanPage(channel.getLong("channel_id"),0,0))
        assertTrue(page.getBoolean("done") || page.getLong("next_id") > 0)
        val row = app.database.videos(limit=1).getJSONObject(0)
        val intent = Intent(context, PlayerActivity::class.java).putExtra("key", row.getString("key")).putExtra("title", "真实视频链路验证")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            fun ready(): Boolean { var ready=false; scenario.onActivity { activity ->
                val p=activity.testPlayer()
                lastState="controller=${p != null} state=${p?.playbackState} playing=${p?.isPlaying} playWhenReady=${p?.playWhenReady} suppression=${p?.playbackSuppressionReason} position=${p?.currentPosition} buffered=${p?.bufferedPosition}"
                p?.playerError?.let { fail("playback error: ${it.errorCodeName}") }
                ready=p?.let { it.playbackState == Player.STATE_READY && it.isPlaying } == true
            }; return ready }
            await(90) { ready() }
            var target=15000L
            scenario.onActivity { activity ->
                val p=activity.testPlayer()!!
                val duration=p.duration
                if (duration > 0 && duration != C.TIME_UNSET) target=minOf(60000L,duration/3)
                val audio=p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val message="connected=true dialogs=${channels.length()} page_messages=${page.optInt("messages")} indexed=${app.database.count()} audio_groups=${audio.size} audio_selected=${audio.any { it.isSelected }} duration_ms=$duration"
                instrumentation.sendStatus(0,Bundle().apply { putString("stream",message+"\n") })
                p.seekTo(target)
            }
            fun position(): Long { var value=0L; scenario.onActivity { value=it.testPlayer()!!.currentPosition }; return value }
            await(90) { ready() && position() >= target + 500 }
            scenario.onActivity { it.testPlayer()!!.seekTo(3000) }
            await(90) { ready() && position() in 3500..10000 }
            scenario.onActivity { PlaybackService.stopPlayback(it) }
        }
    }
}
