package dev.tgproxy.mobile

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CorePlaybackTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as TgApplication

    @After fun stopTestPlayback() {
        // long: 断言失败也需要结束合成媒体，否则下一个用例会接到前一次遗留的播放器。
        InstrumentationRegistry.getInstrumentation().runOnMainSync { PlaybackService.stopPlayback(context) }
    }

    private fun await(timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition()) { if (System.currentTimeMillis() > deadline) fail("condition timed out"); Thread.sleep(100) }
    }

    @Test fun localRangeAuthenticationAndByteIdentity() {
        await { app.engine != null }
        val engine = app.engine!!
        val uri = app.validationMedia()
        val expected = context.assets.open("validation.mp4").use { it.readBytes() }
        val blocked = URL(uri).openConnection() as HttpURLConnection
        assertEquals(401, blocked.responseCode); blocked.disconnect()
        for (start in listOf(0, 524280, expected.size - 4096)) {
            val connection = URL(uri).openConnection() as HttpURLConnection
            connection.setRequestProperty("Authorization", "Bearer ${engine.token()}")
            connection.setRequestProperty("Range", "bytes=$start-${start + 4095}")
            assertEquals(206, connection.responseCode)
            val result = connection.inputStream.use { it.readBytes() }
            assertArrayEquals(expected.copyOfRange(start, start + 4096), result)
            connection.disconnect()
        }
        assertTrue(JSONObject(engine.status()).getLong("range_requests") >= 3)
    }

    @Test fun playerCanSeekAndReturnFromBackground() {
        await { app.engine != null }
        val intent = Intent(context, PlayerActivity::class.java).putExtra("validation", true).putExtra("title", "本地测试片 · 设备验证")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            fun position(): Long { var value = -1L; scenario.onActivity { value = it.testPlayer()?.currentPosition ?: -1 }; return value }
            fun playing(): Boolean { var value = false; scenario.onActivity { value = it.testPlayer()?.let { p -> p.playbackState == Player.STATE_READY && p.isPlaying } == true }; return value }
            await { playing() && position() > 500 }
            scenario.onActivity { it.testPlayer()!!.seekTo(20000) }
            await { playing() && position() in 20000..25000 }
            scenario.onActivity { it.testPlayer()!!.seekTo(3000) }
            await { playing() && position() in 3000..7000 }
            val before = position()
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1500)
            var backgroundPosition = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val player = PlaybackService.testPlayer()
                assertNotNull("后台播放器已被释放", player)
                assertTrue("后台播放已停止", player!!.isPlaying)
                backgroundPosition = player.currentPosition
            }
            assertTrue("后台播放位置没有继续前进", backgroundPosition > before + 500)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { playing() && position() >= backgroundPosition }
            scenario.onActivity { PlaybackService.stopPlayback(it) }
        }
    }

    @Test fun stoppingPlayerAllowsTheNextSessionToStart() {
        await { app.engine != null }
        val intent = Intent(context, PlayerActivity::class.java).putExtra("validation", true).putExtra("title", "重新连接验证")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            fun playing(): Boolean { var value=false; scenario.onActivity { value=it.testPlayer()?.let { p -> p.playbackState == Player.STATE_READY && p.isPlaying } == true }; return value }
            await { playing() }
            scenario.onActivity { PlaybackService.stopPlayback(it) }
        }
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            fun playing(): Boolean { var value=false; scenario.onActivity { value=it.testPlayer()?.let { p -> p.playbackState == Player.STATE_READY && p.isPlaying } == true }; return value }
            await { playing() }
            scenario.onActivity { PlaybackService.stopPlayback(it) }
        }
    }

    @Test fun miniPlayerCanPauseAndRestartAfterEnd() {
        await { app.engine != null }
        val intent = Intent(context, PlayerActivity::class.java).putExtra("validation", true)
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            fun matches(check: (Player) -> Boolean): Boolean {
                var result = false
                scenario.onActivity { result = it.testPlayer()?.let(check) ?: false }
                return result
            }
            await { matches { it.isPlaying } }
            scenario.onActivity { PlaybackService.togglePlayback() }
            await { matches { !it.playWhenReady } }
            scenario.onActivity { PlaybackService.togglePlayback() }
            await { matches { it.isPlaying } }
            scenario.onActivity { it.testPlayer()!!.let { p -> p.seekTo(p.duration) } }
            await { matches { it.playbackState == Player.STATE_ENDED } }
            scenario.onActivity {
                assertFalse(PlaybackService.isPlaybackRequested())
                PlaybackService.togglePlayback()
            }
            await { matches { it.isPlaying && it.currentPosition < 5000 } }
        }
    }

    @Test fun sessionIsEncryptedAndIndexSurvivesReopen() {
        // long: 使用独立测试文件与数据库，设备验证不能覆盖用户后来登录的真实会话和索引。
        val vault = SessionVault(context, "validation-vault.enc", "tgproxy-validation-key")
        val marker = "synthetic-session-for-encryption-check"
        vault.write(JSONObject().put("session", marker))
        assertEquals(marker, vault.read().getString("session"))
        assertFalse(File(context.noBackupFilesDir, "validation-vault.enc").readBytes().toString(Charsets.ISO_8859_1).contains(marker))
        vault.clear()
        val payload = JSONObject().put("channel_id", 1).put("title", "测试频道").put("ref", "@test")
            .put("items", JSONArray().put(JSONObject().put("key", "1_2").put("channel_id", 1).put("message_id", 2).put("name", "视频_01.mp4").put("date", 1).put("size", 100)))
        MediaDatabase(context, "validation.db").use { it.replace(payload) }
        MediaDatabase(context, "validation.db").use {
            assertEquals(1, it.videos("_01").length())
            assertEquals(0, it.videos("' OR 1=1 --").length())
            assertEquals("测试频道", it.channel()!!.getString("title"))
            it.clear()
        }
    }
}
