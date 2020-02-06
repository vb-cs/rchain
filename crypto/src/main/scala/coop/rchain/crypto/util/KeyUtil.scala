package coop.rchain.crypto.util

import java.io.FileWriter
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.ECPublicKeySpec

import cats.effect.Resource
import cats.implicits._
import coop.rchain.crypto.{PrivateKey, PublicKey}
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.signatures.{Secp256k1, SignaturesAlg}
import monix.eval.Task
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.{ECNamedCurveSpec, ECPrivateKeySpec}
import org.bouncycastle.openssl.jcajce.{JcaMiscPEMGenerator, JcaPEMWriter, JcePEMEncryptorBuilder}
import org.bouncycastle.jce.ECPointUtil
import org.bouncycastle.util.io.pem.PemObject

object KeyUtil {

  def writeKeys(
      sk: PrivateKey,
      pk: PublicKey,
      sigAlgorithm: SignaturesAlg,
      password: String,
      privateKeyPath: Path,
      publicKeyPath: Path
  ): Task[Unit] = {
    // Equivalent of using
    // 1. `openssl ec -in key.pem -out privateKey.pem -aes256`
    // 2. `openssl ec -in privateKey.pem -pubout >> publicKey.pem`
    val encryptor =
      new JcePEMEncryptorBuilder("AES-256-CBC")
        .setSecureRandom(SecureRandomUtil.secureRandomNonBlocking)
        .setProvider(new BouncyCastleProvider())
        .build(password.toCharArray)
    for {
      keyPairs <- sigAlgorithm match {
                   case Secp256k1 =>
                     val s               = new BigInteger(Base16.encode(sk.bytes), 16)
                     val ecParameterSpec = ECNamedCurveTable.getParameterSpec(Secp256k1.name)
                     val params = new ECNamedCurveSpec(
                       Secp256k1.name,
                       ecParameterSpec.getCurve,
                       ecParameterSpec.getG,
                       ecParameterSpec.getN
                     )
                     val ecPoint        = ECPointUtil.decodePoint(params.getCurve, pk.bytes)
                     val privateKeySpec = new ECPrivateKeySpec(s, ecParameterSpec)
                     val pubKeySpec     = new ECPublicKeySpec(ecPoint, params)
                     val keyFactory     = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider())
                     (
                       keyFactory.generatePrivate(privateKeySpec),
                       keyFactory.generatePublic(pubKeySpec)
                     ).pure[Task]
                   case _ => Task.raiseError(new Exception("Invalid algorithm"))
                 }
      (privateKey, publicKey) = keyPairs
      privatePemGenerator     = new JcaMiscPEMGenerator(privateKey, encryptor)
      publicPemGenerator      = new JcaMiscPEMGenerator(publicKey)
      priPemObj               = privatePemGenerator.generate()
      pubPemObj               = publicPemGenerator.generate()
      _                       <- writePem(privateKeyPath, priPemObj)
      _                       <- writePem(publicKeyPath, pubPemObj)
    } yield ()
  }
  private def writePem(path: Path, pemObject: PemObject) =
    Resource
      .make(
        Task.delay(new JcaPEMWriter(new FileWriter(path.toFile)))
      )(writer => Task.delay(writer.close()))
      .use { writer =>
        Task.delay(writer.writeObject(pemObject))
      }
}
