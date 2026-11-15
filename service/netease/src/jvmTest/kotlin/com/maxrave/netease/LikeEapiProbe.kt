package com.maxrave.netease

import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

/**
 * 红心 EAPI 通道探针(2026-11-15):
 * 真机 3.12.18 weapi /song/like 对普通单曲《慌啥子嘛》(3451961424,村口吴老二)间歇业务码 524。
 * 社区形状对照:
 *  - Melodia(真机抓包,项目既定参考)= eapi /song/like {trackId, like:Boolean},**无 userid**;
 *  - multiPlatformMusicApi = eapi 同端点带 userid;
 *  - MusicBox(zeyugao) = /api/radio/like(仅 FM 反馈语义,MeloX 记录红心不落"我喜欢的音乐",不作对照)。
 *
 * 本探针按判别力排序:回读初始态 → eapi(最小形状) like → 回读 → unlike → 回读 →
 * (最小形状失败才试带 userid 变体)→ weapi(当前 app 逐字段同形状)同刻 A/B →
 * eapi 还原初始态。非 200 一律 dump 完整 body,拿 524 的 msg/message 真值。
 * 只动当事单曲一首的红心,结尾按初始态还原。
 */
class LikeEapiProbe {
    @Test
    fun probe() {
        val cookieFile = File("/tmp/netease_cookies.json")
        if (!cookieFile.exists()) return
        if (!writeProbeEnabled("like_eapi")) return
        val out = StringBuilder()
        kotlinx.coroutines.runBlocking {
            val cookies =
                (kotlinx.serialization.json.Json.parseToJsonElement(cookieFile.readText())
                    as kotlinx.serialization.json.JsonObject).entries.associate { (k, v) ->
                    k to (v as kotlinx.serialization.json.JsonPrimitive).content
                }
            val client = NeteaseClient(cookieProvider = { cookies })

            fun codeOf(body: kotlinx.serialization.json.JsonObject) =
                (body["code"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun msgOf(body: kotlinx.serialization.json.JsonObject): String {
                val msg = (body["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val message = (body["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                return "msg=$msg message=$message"
            }
            suspend fun readbackContains(uid: Long, id: Long, label: String): Boolean? {
                kotlinx.coroutines.delay(1000)
                val ids = client.userLikedSongIds(uid).getOrNull()?.toSet()
                return if (ids == null) {
                    out.appendLine("$label readback ERR")
                    null
                } else {
                    val hit = ids.contains(id)
                    out.appendLine("$label readback: contains=$hit size=${ids.size}")
                    hit
                }
            }

            // 0. 账号 + 当事曲侧写(服务端视角的版权/播客标记)
            // weapi /user/account 已 404(2026-11-15 探针实测"接口未找到",服务端下线);
            // 明文 /nuser/account/get 同数据(curl 验证活)。
            val accountBody = client.callApi("/nuser/account/get", emptyMap())
            val uid = accountBody.obj("profile")?.get("userId").nLong()
            out.appendLine("uid=$uid accountBody=${accountBody.toString().take(200)}")
            val target = 3451961424L
            val detail = client.callWeApi("/v3/song/detail", mapOf("c" to "[{\"id\":$target}]"))
            detail.array("songs")?.firstOrNull()?.jsonObject?.let {
                out.appendLine("song: ${it.str("name")} ar=${it.array("ar")?.mapNotNull { a -> a.jsonObject.str("name") }}")
            }
            detail.array("privileges")?.firstOrNull()?.jsonObject?.let { p ->
                out.appendLine("privilege: st=${p["st"]} fee=${p["fee"]} cp=${p["cp"]} cs=${p["cs"]} pl=${p["pl"]}")
            }
            if (uid == null) {
                File("/tmp/like_eapi.txt").writeText(out.toString())
                return@runBlocking
            }

            // 1. 初始红心态(还原基准)
            val before = client.userLikedSongIds(uid).getOrNull()?.toSet()
            if (before == null) {
                out.appendLine("initial readback ERR — abort")
                File("/tmp/like_eapi.txt").writeText(out.toString())
                return@runBlocking
            }
            out.appendLine("liked-before: contains=${before.contains(target)} size=${before.size}")

            // 2. eapi 最小形状(Melodia) like(true)
            val eapiLike = client.callEApi("/song/like", mapOf("trackId" to target, "like" to true))
            val eapiLikeCode = codeOf(eapiLike)
            out.appendLine("eapi(minimal) like(true): code=$eapiLikeCode ${msgOf(eapiLike)} body=${eapiLike.toString().take(300)}")
            val eapiMinimalWorks = eapiLikeCode == "200"
            readbackContains(uid, target, "after-eapi-like")

            // 3. eapi 最小形状 unlike(false)
            val eapiUnlike = client.callEApi("/song/like", mapOf("trackId" to target, "like" to false))
            out.appendLine("eapi(minimal) like(false): code=${codeOf(eapiUnlike)} ${msgOf(eapiUnlike)}")
            readbackContains(uid, target, "after-eapi-unlike")

            // 4. 最小形状失败才补带 userid 变体(multiPlatformMusicApi 形状)
            if (!eapiMinimalWorks) {
                kotlinx.coroutines.delay(1000)
                val withUid = client.callEApi("/song/like", mapOf("trackId" to target, "userid" to uid, "like" to true))
                out.appendLine("eapi(+userid) like(true): code=${codeOf(withUid)} ${msgOf(withUid)} body=${withUid.toString().take(300)}")
                readbackContains(uid, target, "after-eapi-uid-like")
                val withUidOff = client.callEApi("/song/like", mapOf("trackId" to target, "userid" to uid, "like" to false))
                out.appendLine("eapi(+userid) like(false): code=${codeOf(withUidOff)} ${msgOf(withUidOff)}")
                readbackContains(uid, target, "after-eapi-uid-unlike")
            }

            // 5. weapi A/B:当前 app 实现逐字段同形状(like=字符串,无 userid),同刻对照 + 完整 body
            kotlinx.coroutines.delay(1000)
            val weapiLike = client.callWeApi("/song/like", mapOf("trackId" to target, "like" to "true"))
            out.appendLine("weapi like(true): code=${codeOf(weapiLike)} ${msgOf(weapiLike)} body=${weapiLike.toString().take(300)}")
            readbackContains(uid, target, "after-weapi-like")

            // 6. 还原初始态——走生产函数 likeSong(切换后=eapi),顺带端到端实证新实现
            val restore = client.likeSong(target, before.contains(target))
            out.appendLine("restore via likeSong(${before.contains(target)}): ${restore.getOrDefault(false)}")
            readbackContains(uid, target, "after-restore(expect=${before.contains(target)})")
        }
        File("/tmp/like_eapi.txt").writeText(out.toString())
    }
}
