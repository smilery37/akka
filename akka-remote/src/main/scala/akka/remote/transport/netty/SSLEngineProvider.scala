/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport.netty

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom

import scala.annotation.tailrec
import scala.util.Try

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.annotation.ApiMayChange
import akka.event.LogMarker
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.RemoteTransport
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransportException
import akka.remote.artery.tcp.SecureRandomFactory
import akka.remote.security.provider.AkkaProvider
import akka.remote.transport.AbstractTransportAdapter
import akka.stream.IgnoreComplete
import akka.stream.TLSClosing
import akka.stream.TLSRole
import com.typesafe.config.Config
import com.typesafe.sslconfig.ssl.SSLConfigSettings
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

@ApiMayChange trait SSLEngineProvider {

  def createServerSSLEngine(): SSLEngine

  def createClientSSLEngine(): SSLEngine

}

/**
 * Config in akka.remote.netty.ssl.security
 *
 * Subclass may override protected methods to replace certain parts, such as key and trust manager.
 */
@ApiMayChange class ConfigSSLEngineProvider(
  protected val log:    MarkerLoggingAdapter,
  private val settings: SSLSettings) extends SSLEngineProvider {

  def this(system: ActorSystem) = this(
    Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName),
    new SSLSettings(system.settings.config.getConfig("akka.remote.netty.ssl.security"))
  )

  import settings._

  private lazy val sslContext: SSLContext = {
    try {
      val rng = createSecureRandom()
      val ctx = SSLContext.getInstance(SSLProtocol)
      ctx.init(keyManagers, trustManagers, rng)
      ctx
    } catch {
      case e: FileNotFoundException    ⇒ throw new RemoteTransportException("Server SSL connection could not be established because key store could not be loaded", e)
      case e: IOException              ⇒ throw new RemoteTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException ⇒ throw new RemoteTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
    }
  }

  /**
   * Subclass may override to customize loading of `KeyStore`
   */
  protected def loadKeystore(filename: String, password: String): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    val fin = Files.newInputStream(Paths.get(filename))
    try keyStore.load(fin, password.toCharArray) finally Try(fin.close())
    keyStore
  }

  /**
   * Subclass may override to customize `KeyManager`
   */
  protected def keyManagers: Array[KeyManager] = {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    factory.init(loadKeystore(SSLKeyStore, SSLKeyStorePassword), SSLKeyPassword.toCharArray)
    factory.getKeyManagers
  }

  /**
   * Subclass may override to customize `TrustManager`
   */
  protected def trustManagers: Array[TrustManager] = {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(loadKeystore(SSLTrustStore, SSLTrustStorePassword))
    trustManagerFactory.getTrustManagers
  }

  def createSecureRandom(): SecureRandom =
    SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)

  override def createServerSSLEngine(): SSLEngine =
    createSSLEngine(akka.stream.Server)

  override def createClientSSLEngine(): SSLEngine =
    createSSLEngine(akka.stream.Client)

  private def createSSLEngine(role: TLSRole): SSLEngine = {
    createSSLEngine(sslContext, role)
  }

  private def createSSLEngine(
    sslContext: SSLContext,
    role:       TLSRole,
    closing:    TLSClosing = IgnoreComplete): SSLEngine = {

    val engine = sslContext.createSSLEngine()

    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if ((role != akka.stream.Client) && SSLRequireMutualAuthentication)
      engine.setNeedClientAuth(true)

    engine
  }

}

