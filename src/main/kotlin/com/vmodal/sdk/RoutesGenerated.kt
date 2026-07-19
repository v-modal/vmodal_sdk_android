// GENERATED — DO NOT EDIT. Anti-grep only.
package com.vmodal.sdk

import java.util.Base64

internal data class GeneratedRoute(
    val id: String,
    val method: String,
    val encodedPath: String,
    val category: String,
    val source: String,
) {
    val path: String by lazy(LazyThreadSafetyMode.PUBLICATION) { RoutesGenerated.strDecode(encodedPath) }
}

internal object RoutesGenerated {
    internal const val ID_ADMIN_CACHE_STATS = "r_515462e8d07e0eab5ad96860"
    internal const val ID_ADMIN_USAGE = "r_c942c0f7f923040f023f2e90"
    internal const val ID_ADMIN_USER_STATS = "r_762f36a1067e11e70bd11509"
    internal const val ID_AUTH_AUTH_CHECK = "r_e3babb3ecdeee3c20bdb38bf"
    internal const val ID_AUTH_HEALTH = "r_61d3d4a390c13c6faf059cf4"
    internal const val ID_AUTH_ME = "r_198d45d0514fe8af2c8cee21"
    internal const val ID_COLLECTIONS_ADD_ASSETS = "r_3dfe63ccd1753bcc7d2a9832"
    internal const val ID_COLLECTIONS_AUTO_INDEX_GET = "r_10852c17e7a280fa3cc0c6c3"
    internal const val ID_COLLECTIONS_AUTO_INDEX_SET = "r_5825b299a2408f9ae5eece3a"
    internal const val ID_COLLECTIONS_CREATE = "r_fe88f73d8a268647adefd96d"
    internal const val ID_COLLECTIONS_DELETE = "r_2f601c6479c73cad4e04e20f"
    internal const val ID_COLLECTIONS_EDIT = "r_ebfb97e5e399ca51cc35934b"
    internal const val ID_COLLECTIONS_LIST_GROUPS = "r_a13ebd3667dc3b229b5a413a"
    internal const val ID_COLLECTIONS_UPDATE_DESCRIPTION = "r_d027f1bccb2e9b0274980a6b"
    internal const val ID_COLLECTIONS_UPLOAD_FILE = "r_5d89172fe0aaaab52d0840c5"
    internal const val ID_COLLECTIONS_UPLOAD_FOLDER = "r_0a872bf74184585d06848a29"
    internal const val ID_COLLECTIONS_UPLOAD_GOOGLE_DRIVE_FOLDER = "r_f7cb98520536542fad6b5416"
    internal const val ID_COLLECTIONS_UPLOAD_METADATA_JSONL = "r_f8cf4dbe1ce6800ce51936e5"
    internal const val ID_COLLECTIONS_VIDEO_UPLOAD_DONE = "r_238e664b578855ed9d8a59ec"
    internal const val ID_COLLECTIONS_VIDEO_UPLOAD_PRESIGN = "r_00b011c1b8ae637c3261e139"
    internal const val ID_GDRIVE_PRIVATE_AUTH_URL = "r_3087d0bb22d83cc2f070fd04"
    internal const val ID_GDRIVE_PRIVATE_DOWNLOAD = "r_ccbfc626cdbf21f3b6121d6b"
    internal const val ID_IMAGES_GET_IMAGE_BULK_FROM_URLS = "r_7fecd9c33ed5b6946de2c509"
    internal const val ID_IMAGES_GET_IMAGE_FROM_URL = "r_c62b5df0ef0722ec2ec39011"
    internal const val ID_IMAGES_GET_URL = "r_14c6adb9e9fc5784afbcba43"
    internal const val ID_IMAGES_GET_URL_BULK = "r_30c9d8c2a278319e97f8bfdf"
    internal const val ID_INDEXES_CREATE_INDEX = "r_e2706e417fc50cc46e8804f0"
    internal const val ID_INDEXES_DELETE_INDEX = "r_4d79e1b344221c4aa5bc9435"
    internal const val ID_INDEXES_EMBEDDING_MODELS = "r_8dddee065a4ecd64fbfe6c0f"
    internal const val ID_INDEXES_INDEX_STATUS = "r_b29f5cff486076b110a5ce2b"
    internal const val ID_INDEXES_JOBS_LIST = "r_861397a02b351208c043e216"
    internal const val ID_METADATA_INTERNAL_FALLBACK = "r_4d2b5e822cecd3f939ffb372"
    internal const val ID_MULTIPART_ABORT = "r_150822d8ad4e38af4caf2ff6"
    internal const val ID_MULTIPART_COMPLETE = "r_d35dcd10e82c563cd80f345c"
    internal const val ID_MULTIPART_CREATE = "r_245a3d60ef68d5cf514be3d1"
    internal const val ID_MULTIPART_SIGN_PARTS = "r_641445e5469ca46c10dcc74c"
    internal const val ID_MULTIPART_STATUS = "r_0237721593f36bd847db6907"
    internal const val ID_R2_CREDENTIALS = "r_2ad48b7746fc5150c62e9534"
    internal const val ID_R2_PRESIGN_UPLOAD_FILE = "r_ac2c889f160950abf98ed649"
    internal const val ID_R2_PRESIGN_UPLOAD_FOLDER_VIDEO = "r_94c3066ac5d1c3f4590835bc"
    internal const val ID_SEARCHES_SEARCH_VIDEO = "r_1ef9019288bff9b114b6730c"
    internal const val ID_SQL_QUERY = "r_b32f658f81c97a2a9d38d3ab"

    internal const val PUBLIC_GATEWAY_URL_HASH = "aHR0cHM6Ly9zZWFyY2hhcGktdGVzdC52LW1vZGFsLmNvbQ=="
    internal const val DEV_GATEWAY_URL_HASH = "aHR0cDovLzEyNy4wLjAuMTozMDk5"
    internal const val EXTERNAL_PREFIX_HASH = "L2FwaS9leHRlcm5hbC92MQ=="
    internal const val USERS_API_PREFIX_HASH = "L2FwaS92MQ=="
    internal const val GATEWAY_PROXY_SUFFIX_HASH = "L2FwaS92MS9wcm94eS9zZWFyY2hfYXBp"

    private val rows: List<GeneratedRoute> = listOf(
        GeneratedRoute("r_515462e8d07e0eab5ad96860", "GET", "L2FkbWluL2NhY2hlL3N0YXRz", "usersApi", "users_api"),
        GeneratedRoute("r_c942c0f7f923040f023f2e90", "GET", "L2FkbWluL3VzYWdl", "usersApi", "users_api"),
        GeneratedRoute("r_762f36a1067e11e70bd11509", "GET", "L2FkbWluL3VzZXItc3RhdHM=", "active", "upstream"),
        GeneratedRoute("r_e3babb3ecdeee3c20bdb38bf", "GET", "L2hlYWx0aA==", "active", "upstream"),
        GeneratedRoute("r_61d3d4a390c13c6faf059cf4", "GET", "L2hlYWx0aA==", "active", "upstream"),
        GeneratedRoute("r_198d45d0514fe8af2c8cee21", "GET", "L2F1dGgvbWU=", "usersApi", "users_api"),
        GeneratedRoute("r_3dfe63ccd1753bcc7d2a9832", "POST", "L2NvbGxlY3Rpb24ve2NvbGxlY3Rpb25faWR9L2Fzc2V0cy9jcmVhdGU=", "active", "upstream"),
        GeneratedRoute("r_10852c17e7a280fa3cc0c6c3", "GET", "L2NvbGxlY3Rpb24vYXV0b19pbmRleA==", "disabled", "upstream"),
        GeneratedRoute("r_5825b299a2408f9ae5eece3a", "POST", "L2NvbGxlY3Rpb24vYXV0b19pbmRleA==", "disabled", "upstream"),
        GeneratedRoute("r_fe88f73d8a268647adefd96d", "NONE", "", "disabled", "sdk_contract"),
        GeneratedRoute("r_2f601c6479c73cad4e04e20f", "DELETE", "L2NvbGxlY3Rpb24vZGVsZXRl", "active", "upstream"),
        GeneratedRoute("r_ebfb97e5e399ca51cc35934b", "NONE", "", "disabled", "sdk_contract"),
        GeneratedRoute("r_a13ebd3667dc3b229b5a413a", "GET", "L2NvbGxlY3Rpb24vZ3JvdXBz", "active", "upstream"),
        GeneratedRoute("r_d027f1bccb2e9b0274980a6b", "POST", "L2NvbGxlY3Rpb24vZGVzY3JpcHRpb24vdXBkYXRl", "active", "upstream"),
        GeneratedRoute("r_5d89172fe0aaaab52d0840c5", "POST", "L2NvbGxlY3Rpb24vdXBsb2Fk", "active", "upstream"),
        GeneratedRoute("r_0a872bf74184585d06848a29", "POST", "L3VwbG9hZC9mb2xkZXI=", "disabled", "upstream"),
        GeneratedRoute("r_f7cb98520536542fad6b5416", "POST", "L2NvbGxlY3Rpb24vdXBsb2FkL2dvb2dsZV9kcml2ZQ==", "deprecated", "upstream"),
        GeneratedRoute("r_f8cf4dbe1ce6800ce51936e5", "POST", "L2NvbGxlY3Rpb24vdXBsb2FkL21ldGFkYXRh", "active", "upstream"),
        GeneratedRoute("r_238e664b578855ed9d8a59ec", "POST", "L2NvbGxlY3Rpb24vdXBsb2FkL2RvbmU=", "signedSingle", "sdk_python"),
        GeneratedRoute("r_00b011c1b8ae637c3261e139", "POST", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9nZXRfc2lnbmVkX3VybA==", "signedSingle", "sdk_python"),
        GeneratedRoute("r_3087d0bb22d83cc2f070fd04", "POST", "L2dkcml2ZS9wcml2YXRlL2F1dGgtdXJs", "disabled", "upstream"),
        GeneratedRoute("r_ccbfc626cdbf21f3b6121d6b", "POST", "L2dkcml2ZS9wcml2YXRlL2ZvbGRlci9kb3dubG9hZA==", "disabled", "upstream"),
        GeneratedRoute("r_7fecd9c33ed5b6946de2c509", "POST", "L2ltYWdlL2dldF9pbWFnZV9idWxr", "image", "apionly_serve_img.py"),
        GeneratedRoute("r_c62b5df0ef0722ec2ec39011", "POST", "L2ltYWdlL2dldF9pbWFnZQ==", "image", "apionly_serve_img.py"),
        GeneratedRoute("r_14c6adb9e9fc5784afbcba43", "POST", "L2ltYWdlL2dldF91cmw=", "image", "apionly_serve_img.py"),
        GeneratedRoute("r_30c9d8c2a278319e97f8bfdf", "POST", "L2ltYWdlL2dldF91cmxfYnVsaw==", "image", "apionly_serve_img.py"),
        GeneratedRoute("r_e2706e417fc50cc46e8804f0", "POST", "L2luZGV4YXRpb24vam9iL2NyZWF0ZQ==", "active", "upstream"),
        GeneratedRoute("r_4d79e1b344221c4aa5bc9435", "DELETE", "L2luZGV4YXRpb24vaW5kZXgvZGVsZXRl", "active", "upstream"),
        GeneratedRoute("r_8dddee065a4ecd64fbfe6c0f", "GET", "L2luZGV4ZXMvZW1iZWRkaW5nX21vZGVscw==", "disabled", "upstream"),
        GeneratedRoute("r_b29f5cff486076b110a5ce2b", "GET", "L2luZGV4YXRpb24vam9iL3tqb2JfaWR9", "active", "upstream"),
        GeneratedRoute("r_861397a02b351208c043e216", "GET", "L2luZGV4YXRpb24vam9icw==", "active", "upstream"),
        GeneratedRoute("r_4d2b5e822cecd3f939ffb372", "POST", "L2FwaS9pbnRlcm5hbC92MS9jb2xsZWN0aW9uL3VwbG9hZC9tZXRhZGF0YQ==", "active", "sdk_python"),
        GeneratedRoute("r_150822d8ad4e38af4caf2ff6", "POST", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9tdWx0aXBhcnQvYWJvcnQ=", "multipartExperimental", "sdk_python"),
        GeneratedRoute("r_d35dcd10e82c563cd80f345c", "POST", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9tdWx0aXBhcnQvY29tcGxldGU=", "multipartExperimental", "sdk_python"),
        GeneratedRoute("r_245a3d60ef68d5cf514be3d1", "POST", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9tdWx0aXBhcnQvY3JlYXRl", "multipartExperimental", "sdk_python"),
        GeneratedRoute("r_641445e5469ca46c10dcc74c", "POST", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9tdWx0aXBhcnQvc2lnbl9wYXJ0cw==", "multipartExperimental", "sdk_python"),
        GeneratedRoute("r_0237721593f36bd847db6907", "GET", "L2NvbGxlY3Rpb25zL2V4dGVybmFsX3VwbG9hZF9tdWx0aXBhcnQvc3RhdHVz", "multipartExperimental", "sdk_python"),
        GeneratedRoute("r_2ad48b7746fc5150c62e9534", "GET", "L2dldF9yMl9jcmVkZW50aWFscy8=", "usersApi", "users_api"),
        GeneratedRoute("r_ac2c889f160950abf98ed649", "GET", "L3VwbG9hZF9maWxlLw==", "usersApi", "users_api"),
        GeneratedRoute("r_94c3066ac5d1c3f4590835bc", "POST", "L3VwbG9hZF9mb2xkZXJfdmlkZW8v", "usersApi", "users_api"),
        GeneratedRoute("r_1ef9019288bff9b114b6730c", "POST", "L3NlYXJjaA==", "active", "upstream"),
        GeneratedRoute("r_b32f658f81c97a2a9d38d3ab", "POST", "L3NxbC9xdWVyeQ==", "disabled", "upstream"),
    )
    private val rowsById: Map<String, GeneratedRoute> = rows.associateBy { it.id }

    internal val entries: List<GeneratedRoute> = rows
    internal val public_gateway_url: String = strDecode(PUBLIC_GATEWAY_URL_HASH)
    internal val dev_gateway_url: String = strDecode(DEV_GATEWAY_URL_HASH)
    internal val external_prefix: String = strDecode(EXTERNAL_PREFIX_HASH)
    internal val users_api_prefix: String = strDecode(USERS_API_PREFIX_HASH)
    internal val gateway_proxy_suffix: String = strDecode(GATEWAY_PROXY_SUFFIX_HASH)

    internal fun entry(id: String): GeneratedRoute =
        rowsById[id] ?: error("generated route ID is missing")

    internal fun path(id: String): String = entry(id).path

    internal fun methodPath(id: String): Pair<String, String> {
        val row = entry(id)
        return row.method to row.path
    }

    internal fun strDecode(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
}
