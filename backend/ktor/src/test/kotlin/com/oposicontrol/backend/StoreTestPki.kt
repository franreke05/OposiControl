package com.oposicontrol.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * PKI de PRUEBA generada en cada ejecucion (nunca claves ni certificados reales de Apple / Google):
 *  - una "raiz de Apple" de pega, un intermedio con el OID WWDR (1.2.840.113635.100.6.2.1) y una hoja con el OID de firmante
 *    (1.2.840.113635.100.6.11.1), como exige el `AppleExtensionCertPathChecker` de la libreria oficial;
 *  - un JWS ES256 con cabecera `x5c` [hoja, intermedio, raiz], como los de StoreKit 2 / App Store Server Notifications V2;
 *  - una clave RSA para firmar tokens OIDC "de Google" de prueba.
 */
internal class StoreTestPki(withLeafOid: Boolean = true, withIntermediateOid: Boolean = true) {
    val rootKeys: KeyPair = ec()
    val root: X509Certificate = cert("CN=Test Apple Root CA", rootKeys, "CN=Test Apple Root CA", rootKeys, ca = true, oid = null)
    private val intermediateKeys = ec()
    val intermediate: X509Certificate = cert("CN=Test WWDR", intermediateKeys, "CN=Test Apple Root CA", rootKeys, ca = true, oid = if (withIntermediateOid) WWDR_OID else null)
    private val leafKeys = ec()
    val leaf: X509Certificate = cert("CN=Test StoreKit Signer", leafKeys, "CN=Test WWDR", intermediateKeys, ca = false, oid = if (withLeafOid) SIGNER_OID else null)

    val rootDer: ByteArray get() = root.encoded

    /** JWS como el de Apple: ES256, x5c = [hoja, intermedio, raiz] en base64 estandar. */
    fun sign(payloadJson: String, chain: List<X509Certificate> = listOf(leaf, intermediate, root)): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val x5c = chain.joinToString(",") { "\"" + Base64.getEncoder().encodeToString(it.encoded) + "\"" }
        val header = enc.encodeToString("""{"alg":"ES256","x5c":[$x5c]}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        val signature = Algorithm.ECDSA256(null, leafKeys.private as ECPrivateKey).sign(header.toByteArray(), payload.toByteArray())
        return "$header.$payload.${enc.encodeToString(signature)}"
    }

    companion object {
        const val WWDR_OID = "1.2.840.113635.100.6.2.1"
        const val SIGNER_OID = "1.2.840.113635.100.6.11.1"
        private var serial = 1L

        private fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        private fun cert(subject: String, keys: KeyPair, issuer: String, issuerKeys: KeyPair, ca: Boolean, oid: String?): X509Certificate {
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                X500Name(issuer), BigInteger.valueOf(serial++), Date.from(now.minusSeconds(86_400L * 400)), Date.from(now.plusSeconds(86_400L * 400)),
                X500Name(subject), keys.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
            oid?.let { builder.addExtension(ASN1ObjectIdentifier(it), false, DERNull.INSTANCE) }
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}

/** Claves RSA "de Google" de prueba para el OIDC de Pub/Sub. */
internal class GoogleTestKeys {
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val kid = "test-kid-1"
    val public: RSAPublicKey get() = pair.public as RSAPublicKey
    val source = GoogleKeySource { if (it == kid) public else null }

    fun token(
        aud: String = AUDIENCE,
        iss: String = "https://accounts.google.com",
        email: String = SERVICE_ACCOUNT,
        emailVerified: Boolean = true,
        expiresIn: Long = 3600,
        kid: String = this.kid,
        key: RSAPrivateKey = pair.private as RSAPrivateKey,
    ): String = JWT.create()
        .withKeyId(kid)
        .withIssuer(iss)
        .withAudience(aud)
        .withClaim("email", email)
        .withClaim("email_verified", emailVerified)
        .withIssuedAt(Date.from(Instant.now().minusSeconds(10)))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(expiresIn)))
        .sign(Algorithm.RSA256(null, key))

    companion object {
        const val AUDIENCE = "https://bot.example.test/api/v1/webhooks/store/google-play"
        const val SERVICE_ACCOUNT = "rtdn-push@example-project.iam.gserviceaccount.com"
        fun otherKey(): RSAPrivateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateKey
    }
}
