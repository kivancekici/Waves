package com.wavesplatform.generator

import java.io.File
import java.net.InetSocketAddress

import com.google.common.base.CaseFormat
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.settings.loadConfig
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.EnumerationReader._
import net.ceedubs.ficus.readers.{CollectionReaders, ValueReader}
import org.slf4j.LoggerFactory
import scorex.account.PrivateKeyAccount
import scorex.crypto.encode.Base58
import scorex.transaction.TransactionParser.TransactionType
import scorex.utils.LoggerFacade
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

import scala.concurrent.duration.FiniteDuration

case class GeneratorSettings(chainId: Char,
                             accounts: Seq[PrivateKeyAccount],
                             sendTo: Seq[InetSocketAddress],
                             iterations: Int,
                             delay: FiniteDuration,
                             mode: Mode.Value,
                             narrow: NarrowTransactionGenerator.Settings,
                             wide: WideTransactionGenerator.Settings,
                             dynWide: DynamicWideTransactionGenerator.Settings)

object GeneratorSettings {
  val configPath: String = "generator"

  private implicit val inetSocketAddressReader: ValueReader[InetSocketAddress] = { (config: Config, path: String) =>
    new InetSocketAddress(
      config.as[String](s"$path.address"),
      config.as[Int](s"$path.port")
    )
  }

  implicit val mapR: ValueReader[Map[TransactionType.Value, Double]] = {
    val converter = CaseFormat.LOWER_HYPHEN.converterTo(CaseFormat.UPPER_CAMEL)
    def toTxType(key: String): TransactionType.Value = TransactionType.withName(s"${converter.convert(key)}Transaction")

    CollectionReaders.mapValueReader[Double].map { xs =>
      xs.map { case (k, v) => toTxType(k) -> v }
    }
  }

  def fromConfig(config: Config): GeneratorSettings = {
    GeneratorSettings(
      chainId = config.as[String](s"$configPath.chainId").head, // Char?
      accounts = config.as[List[String]](s"$configPath.accounts").map(s => PrivateKeyAccount(Base58.decode(s).get)),
      sendTo = config.as[Seq[InetSocketAddress]](s"$configPath.send-to"),
      iterations = config.as[Int](s"$configPath.iterations"),
      delay = config.as[FiniteDuration](s"$configPath.delay"),
      mode = config.as[Mode.Value](s"$configPath.mode"),
      narrow = config.as[NarrowTransactionGenerator.Settings](s"$configPath.narrow"),
      wide = config.as[WideTransactionGenerator.Settings](s"$configPath.wide"),
      dynWide = config.as[DynamicWideTransactionGenerator.Settings](s"$configPath.dyn-wide")
    )
  }

  private val log = LoggerFacade(LoggerFactory.getLogger(getClass))

  def readConfig(userConfigPath: Option[String]): Config = {
    log.info(s"Loading config from path: $userConfigPath")

    val maybeConfigFile = for {
      filename <- userConfigPath
      file = new File(filename)
      if file.exists
    } yield file

    val config = maybeConfigFile match {
      // if no user config is supplied, the library will handle overrides/application/reference automatically
      case None =>
        log.info("No config found/provided, using reference.conf automatically")
        ConfigFactory.load()
      // application config needs to be resolved wrt both system properties *and* user-supplied config.
      case Some(file) =>
        val cfg = ConfigFactory.parseFile(file)
        if (!cfg.hasPath("generator")) {
          log.error("Malformed configuration file was provided! Aborting!")
          System.exit(1)
        }
        loadConfig(cfg)
    }
    config
  }
}
