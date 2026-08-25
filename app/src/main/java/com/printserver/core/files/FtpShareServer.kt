package com.printserver.core.files

import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.PrinterLog
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpShareServer(
    private val port: () -> Int,
    private val root: () -> File,
    private val username: () -> String,
    private val passwordHash: () -> String,
) {
    companion object { private const val TAG = "FtpShare" }

    private var server: FtpServer? = null
    val isRunning: Boolean get() = server != null && !server!!.isStopped

    fun start() {
        if (isRunning) return
        val factory = FtpServerFactory()
        val lf = ListenerFactory()
        lf.setPort(port())
        factory.addListener("default", lf.createListener())
        factory.userManager = SingleUserManager(username(), passwordHash(), root())
        server = factory.createServer().also { it.start() }
        PrinterLog.i(TAG, "FTP listening on 0.0.0.0:${port()} root=${root().absolutePath}")
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        PrinterLog.i(TAG, "Stopped")
    }

    private class SingleUserManager(
        private val name: String,
        private val passHash: String,
        private val home: File,
    ) : UserManager {

        override fun getUserByName(username: String?): User {
            if (username != name) throw AuthenticationFailedException("unknown user")
            return build()
        }

        override fun getAllUserNames(): Array<String> = arrayOf(name)

        override fun doesExist(username: String?): Boolean = username == name

        override fun getAdminName(): String = name

        override fun isAdmin(username: String?): Boolean = false

        override fun authenticate(authentication: Authentication?): User {
            val upa = authentication as? UsernamePasswordAuthentication
                ?: throw AuthenticationFailedException("unsupported auth")
            if (upa.username != name) throw AuthenticationFailedException("unknown user")
            if (passHash.isNotEmpty()) {
                val supplied = PreferencesManager.sha256(upa.password ?: "")
                if (!supplied.equals(passHash, ignoreCase = true)) throw AuthenticationFailedException("bad password")
            }
            return build()
        }

        override fun save(user: User?) {}
        override fun delete(username: String?) {}

        private fun build(): BaseUser = BaseUser().apply {
            setName(name)
            homeDirectory = home.absolutePath
            authorities = listOf(WritePermission())
        }
    }
}
