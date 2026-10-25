package com.dutchman175.familydashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStreamTest {
    private val token = "B0Z5qXGF1JQDgH"

    @Test
    fun linkKeepsOnlyTheToken() {
        val action = PhotoAlbumUrl.interpret("https://www.icloud.com/sharedalbum/#$token")
        val save = action as PhotoAlbumUrl.Action.Save
        assertEquals(token, save.token)
        assertEquals(PhotoAlbumUrl.Kind.LEGACY, save.kind)
        assertFalse(save.token.contains("icloud"))
        assertFalse(save.token.contains("http"))
    }

    @Test
    fun localeLinkAndBareToken() {
        val locale = PhotoAlbumUrl.interpret("https://www.icloud.com/sharedalbum/en-us/#$token")
        assertEquals(token, (locale as PhotoAlbumUrl.Action.Save).token)
        val bare = PhotoAlbumUrl.interpret(token)
        assertEquals(token, (bare as PhotoAlbumUrl.Action.Save).token)
    }

    @Test
    fun rejectsOtherHostsAndClears() {
        assertTrue(PhotoAlbumUrl.interpret("https://evil.example/sharedalbum/#$token") is PhotoAlbumUrl.Action.Ignore)
        assertTrue(PhotoAlbumUrl.interpret("http://www.icloud.com/sharedalbum/#$token") is PhotoAlbumUrl.Action.Ignore)
        assertTrue(PhotoAlbumUrl.interpret("javascript:alert(1)") is PhotoAlbumUrl.Action.Ignore)
        assertTrue(PhotoAlbumUrl.interpret("") is PhotoAlbumUrl.Action.Clear)
        assertTrue(PhotoAlbumUrl.interpret("clear") is PhotoAlbumUrl.Action.Clear)
        assertTrue(PhotoAlbumUrl.interpret(null) is PhotoAlbumUrl.Action.Clear)
    }

    @Test
    fun redirectHostMustBeApple() {
        assertEquals(
            "p42-sharedstreams.icloud.com",
            SharedStream.redirectHost("""{"X-Apple-MMe-Host":"p42-sharedstreams.icloud.com"}""")
        )
        assertNull(SharedStream.redirectHost("""{"X-Apple-MMe-Host":"evil.example"}"""))
        assertNull(SharedStream.redirectHost("""{"photos":[]}"""))
    }

    @Test
    fun picksScreenSizeNotTheOriginal() {
        val derivatives = JSONObject(
            """
            {
              "thumb": {"checksum": "aaaaaaaaaaaaaaaa", "fileSize": "12000", "width": "320", "height": "240"},
              "screen": {"checksum": "bbbbbbbbbbbbbbbb", "fileSize": "400000", "width": "1920", "height": "1080"},
              "original": {"checksum": "cccccccccccccccc", "fileSize": "9000000", "width": "4032", "height": "3024"}
            }
            """.trimIndent()
        )
        val picked = SharedStream.pick(derivatives)
        assertEquals("bbbbbbbbbbbbbbbb", picked!!.checksum)
        assertEquals(1920, picked.width)
    }

    @Test
    fun usesLargestAvailableWhenNothingReaches1080p() {
        val derivatives = JSONObject(
            """
            {
              "a": {"checksum": "dddddddddddddddd", "fileSize": 1000, "width": 400, "height": 300},
              "b": {"checksum": "eeeeeeeeeeeeeeee", "fileSize": 8000, "width": 900, "height": 1600}
            }
            """.trimIndent()
        )
        val picked = SharedStream.pick(derivatives)
        assertEquals("eeeeeeeeeeeeeeee", picked!!.checksum)
    }

    @Test
    fun skipsVideosAndBuildsPlaylistWithoutTheToken() {
        val body = """
            {
              "photos": [
                {
                  "photoGuid": "2C50FDDE-A9D8-4D28-B99B-4265CF2F1D8A",
                  "mediaAssetType": "video",
                  "derivatives": {
                    "2049": {"checksum": "010ca39ab9acf31d", "fileSize": "100", "width": "1920", "height": "1080"}
                  }
                },
                {
                  "photoGuid": "AABBCCDD-1122-3344-5566-778899000000",
                  "derivatives": {
                    "342": {"checksum": "01f4ef240d7581f2", "fileSize": "43050", "width": "257", "height": "342"},
                    "2049": {"checksum": "010ca39ab9acf31d", "fileSize": "985681", "width": "1537", "height": "2049"}
                  }
                }
              ]
            }
        """.trimIndent()
        val photos = SharedStream.photos(body)
        assertEquals(1, photos.size)
        assertEquals("010ca39ab9acf31d", photos[0].checksum)
        assertEquals(2049, photos[0].height)
        val name = SharedStream.fileName(photos[0].guid)
        assertFalse(name.contains(token))
        assertFalse(name.contains("AABBCCDD"))
        val playlist = SharedStream.playlistJson(true, listOf(name, "../$token.jpg", "not-a-photo"))
        assertTrue(playlist.contains("https://appassets.androidplatform.net/photos/$name"))
        assertFalse(playlist.contains(token))
        assertFalse(playlist.contains(".."))
        assertEquals("""{"enabled":false,"photos":[]}""", SharedStream.playlistJson(false, listOf(name)))
    }

    @Test
    fun assetUrlRejectsAForeignHost() {
        val wanted = setOf("bbbbbbbbbbbbbbbb")
        val good = SharedStream.assetUrls(
            """
            {
              "locations": {"cvws.icloud-content.com": {"scheme": "https", "hosts": ["cvws.icloud-content.com"]}},
              "items": {
                "bbbbbbbbbbbbbbbb": {"url_location": "cvws.icloud-content.com", "url_path": "/S/example.JPG?o=1"},
                "aaaaaaaaaaaaaaaa": {"url_location": "cvws.icloud-content.com", "url_path": "/S/other.JPG"}
              }
            }
            """.trimIndent(),
            wanted
        )
        assertEquals(
            "https://cvws.icloud-content.com/S/example.JPG?o=1",
            good["bbbbbbbbbbbbbbbb"]
        )
        assertFalse(good.containsKey("aaaaaaaaaaaaaaaa"))
        val bad = SharedStream.assetUrls(
            """
            {"items":{"bbbbbbbbbbbbbbbb":{"url_location":"evil.example","url_path":"/x.jpg"}}}
            """.trimIndent(),
            wanted
        )
        assertTrue(bad.isEmpty())
        val http = SharedStream.assetUrls(
            """
            {
              "locations": {"cvws.icloud-content.com": {"scheme": "http"}},
              "items": {"bbbbbbbbbbbbbbbb": {"url_location": "cvws.icloud-content.com", "url_path": "/x.jpg"}}
            }
            """.trimIndent(),
            wanted
        )
        assertTrue(http.isEmpty())
    }

    @Test
    fun newAlbumLinkKeepsOnlyTheToken() {
        val cloudToken = "Abcdefghij1234567890ABCDE"
        val action = PhotoAlbumUrl.interpret("https://photos.icloud.com/shared/album/$cloudToken")
        val save = action as PhotoAlbumUrl.Action.Save
        assertEquals(cloudToken, save.token)
        assertEquals(PhotoAlbumUrl.Kind.CLOUDKIT, save.kind)
        assertFalse(save.token.contains("photos"))
        val slash = PhotoAlbumUrl.interpret("https://photos.icloud.com/shared/album/$cloudToken/")
        assertEquals(cloudToken, (slash as PhotoAlbumUrl.Action.Save).token)
        assertTrue(
            PhotoAlbumUrl.interpret("https://evil.example/shared/album/$cloudToken") is PhotoAlbumUrl.Action.Ignore
        )
        assertTrue(
            PhotoAlbumUrl.interpret("http://photos.icloud.com/shared/album/$cloudToken") is PhotoAlbumUrl.Action.Ignore
        )
    }

    @Test
    fun cloudKitPicksScreenSizeAndSkipsVideo() {
        val resolve = """
            {
              "results": [{
                "zoneID": {"zoneName": "SharedAlbum-1", "ownerRecordName": "owner123", "zoneType": "REGULAR_CUSTOM_ZONE"},
                "anonymousPublicAccess": {
                  "databasePartition": "https://p42-ckdatabasews.icloud.com:443",
                  "token": "anonymous-access-token-value"
                }
              }]
            }
        """.trimIndent()
        val share = CloudKitAlbum.parseResolve(resolve)
        assertEquals("p42-ckdatabasews.icloud.com", share!!.partitionHost)
        assertNull(
            CloudKitAlbum.parseResolve(
                """
                {"results":[{"zoneID":{"zoneName":"SharedAlbum-1","ownerRecordName":"owner123","zoneType":"REGULAR_CUSTOM_ZONE"},
                "anonymousPublicAccess":{"databasePartition":"https://evil.example","token":"anonymous-access-token-value"}}]}
                """.trimIndent()
            )
        )
        val body = """
            {
              "records": [
                {
                  "recordType": "CPLMaster",
                  "recordName": "videoRecord123456",
                  "fields": {
                    "itemType": {"value": "public.mpeg-4"},
                    "resJPEGMedRes": {"value": {"downloadURL": "https://cvws.icloud-content.com/v.jpg", "size": 1000}},
                    "resJPEGMedWidth": {"value": 1920},
                    "resJPEGMedHeight": {"value": 1080}
                  }
                },
                {
                  "recordType": "CPLMaster",
                  "recordName": "photo+id/withBase64==",
                  "fields": {
                    "itemType": {"value": "public.heic"},
                    "resJPEGThumbRes": {"value": {"downloadURL": "https://cvws.icloud-content.com/t.jpg", "size": 80000}},
                    "resJPEGThumbWidth": {"value": 360},
                    "resJPEGThumbHeight": {"value": 480},
                    "resJPEGMedRes": {"value": {"downloadURL": "https://cvws.icloud-content.com/m.jpg", "size": 900000}},
                    "resJPEGMedWidth": {"value": 1536},
                    "resJPEGMedHeight": {"value": 2048},
                    "resOriginalRes": {"value": {"downloadURL": "https://cvws.icloud-content.com/o.jpg", "size": 3000000}},
                    "resOriginalWidth": {"value": 3024},
                    "resOriginalHeight": {"value": 4032}
                  }
                }
              ],
              "continuationMarker": ""
            }
        """.trimIndent()
        val photos = CloudKitAlbum.photos(body)
        assertEquals(1, photos.size)
        assertEquals("https://cvws.icloud-content.com/m.jpg", photos[0].directUrl)
        assertEquals(2048, photos[0].height)
        assertNull(CloudKitAlbum.continuation(body))
        val braced = """
            {
              "records": [{
                "recordType": "CPLMaster",
                "recordName": "photo+id/withBase64==",
                "fields": {
                  "itemType": {"value": "public.heic"},
                  "resJPEGMedRes": {"value": {"downloadURL": "https://cvws-h2.icloud-content.com/B/{f}?o=1", "size": 900000}},
                  "resJPEGMedWidth": {"value": 1536},
                  "resJPEGMedHeight": {"value": 2048}
                }
              }]
            }
        """.trimIndent()
        val kept = CloudKitAlbum.photos(braced)
        assertEquals(1, kept.size)
        assertEquals("https://cvws-h2.icloud-content.com/B/{f}?o=1", kept[0].directUrl)
        val query = CloudKitAlbum.resolveQuery("Abcdefghij1234567890ABCDE")
        assertTrue(query.contains("clientBuildNumber=2638BuildBeta15"))
        assertTrue(query.contains("sharing_url_key="))
        assertFalse(query.contains(" "))
        val quoted = PhotoAlbumUrl.interpret("'https://photos.icloud.com/shared/album/Abcdefghij1234567890ABCDE'")
        assertEquals("Abcdefghij1234567890ABCDE", (quoted as PhotoAlbumUrl.Action.Save).token)
        assertEquals(PhotoAlbumUrl.Kind.CLOUDKIT, quoted.kind)
    }
}
