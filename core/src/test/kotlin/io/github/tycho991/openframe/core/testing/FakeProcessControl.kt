package io.github.tycho991.openframe.core.testing

import io.github.tycho991.openframe.core.platform.ProcessControl
import java.util.concurrent.CopyOnWriteArrayList

/** Records the restarts instead of ending the test JVM. */
class FakeProcessControl : ProcessControl {
    val restarts = CopyOnWriteArrayList<String>()

    override fun restart(reason: String) {
        restarts += reason
    }
}
