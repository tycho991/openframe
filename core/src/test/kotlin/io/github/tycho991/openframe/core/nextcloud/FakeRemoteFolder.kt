package io.github.tycho991.openframe.core.nextcloud

import java.io.File

/** An in-memory [RemoteFolder] for the tests of the sync logic: no sockets, faults on request. */
class FakeRemoteFolder : RemoteFolder {
    private val content = LinkedHashMap<String, Pair<RemoteFile, ByteArray>>()

    /** The paths downloaded so far, in order. */
    val downloads = ArrayList<String>()

    /** Thrown by `list` while set. */
    @Volatile var listFailure: NextcloudException? = null

    /** Called before every download; throws to make it fail. */
    @Volatile var beforeDownload: (RemoteFile) -> Unit = {}

    fun put(
        path: String,
        bytes: ByteArray,
        etag: String = "etag-${bytes.size}-${path.hashCode()}",
        modifiedMs: Long = 1_760_000_000_000L,
    ) {
        content[path] = RemoteFile(path, etag, bytes.size.toLong(), modifiedMs) to bytes
    }

    fun remove(path: String) {
        content.remove(path)
    }

    override fun list(recursive: Boolean): List<RemoteFile> {
        listFailure?.let { throw it }
        return content.values.map { it.first }.filter { recursive || '/' !in it.path }
    }

    override fun download(
        file: RemoteFile,
        target: File,
    ) {
        beforeDownload(file)
        val (_, bytes) = content[file.path] ?: throw NextcloudException(NextcloudException.Kind.NOT_FOUND, "${file.path} was not found")
        downloads += file.path
        target.writeBytes(bytes)
    }
}

/** Clients that hand out the fakes. */
class FakeClients(
    val remote: FakeRemoteFolder = FakeRemoteFolder(),
    val login: LoginFlow = FakeLoginFlow(),
) : NextcloudClients {
    override fun loginFlow(): LoginFlow = login

    override fun remoteFolder(config: StoredNextcloud): RemoteFolder = remote
}

/** A login flow that the test approves by hand. */
class FakeLoginFlow(
    var serverUrl: String = "https://cloud.example.org",
    val loginName: String = "Anna",
    val appPassword: String = "app-pw-1234",
    val userId: String = "anna",
) : LoginFlow {
    @Volatile var approved = false

    @Volatile var startFailure: NextcloudException? = null

    @Volatile var revoked = 0

    val started = ArrayList<String>()

    override fun start(serverUrl: String): LoginStart {
        startFailure?.let { throw it }
        started += serverUrl
        return LoginStart(serverUrl, "$serverUrl/index.php/login/v2/flow/abc", "$serverUrl/index.php/login/v2/poll", "token")
    }

    override fun poll(start: LoginStart): LoginPoll =
        if (approved) LoginPoll.Done(start.serverUrl, loginName, appPassword) else LoginPoll.Pending

    override fun userId(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) = userId

    override fun revoke(
        serverUrl: String,
        loginName: String,
        appPassword: String,
    ) {
        revoked++
    }
}
