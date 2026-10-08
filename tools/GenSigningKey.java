import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.util.encoders.Hex;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;

/**
 * 生成一对用于 Maven Central 制品签名的 PGP 密钥，**不依赖 gpg 命令行**。
 *
 * <p>为什么需要它：Central 强制要求每个制品都有 {@code .asc} 签名，但 Gradle 的 signing 插件
 * 只需要一对内存里的密钥（{@code useInMemoryPgpKeys}），并不需要系统装 gpg。
 * 而 gpg 在某些机器上根本装不上 —— 例如 Homebrew 会把未识别的 macOS 版本直接判为
 * {@code unknown or unsupported macOS version: :dunno}。这个工具就是为了绕开这条死路。
 *
 * <p>用法见 {@code tools/gen-signing-key.sh}。
 */
public final class GenSigningKey {
    public static void main(String[] args) throws Exception {
        String userId = args[0];
        String passphrase = args[1];
        String outDir = args[2];

        Security.addProvider(new BouncyCastleProvider());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC");
        generator.initialize(4096);
        KeyPair keyPair = generator.generateKeyPair();

        PGPKeyPair pgpKeyPair =
                new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, new Date());

        PGPKeyRingGenerator keyRingGenerator = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                // v4 密钥的校验和算法只支持 SHA1，这里传 SHA256 会直接抛
                // "only SHA1 supported for key checksum calculations."
                // （签名摘要另算，见下面签名器的 SHA512）
                new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1),
                null,
                null,
                new JcaPGPContentSignerBuilder(
                        pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA512),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256)
                        .setProvider("BC")
                        .build(passphrase.toCharArray()));

        PGPPublicKeyRing publicRing = keyRingGenerator.generatePublicKeyRing();
        PGPSecretKeyRing secretRing = keyRingGenerator.generateSecretKeyRing();

        try (OutputStream out = new ArmoredOutputStream(
                new FileOutputStream(outDir + "/private.asc"))) {
            secretRing.encode(out);
        }
        try (OutputStream out = new ArmoredOutputStream(
                new FileOutputStream(outDir + "/public.asc"))) {
            publicRing.encode(out);
        }

        PGPPublicKey master = publicRing.getPublicKey();
        System.out.println("KEY_ID=" + Long.toHexString(master.getKeyID()).toUpperCase());
        System.out.println("FINGERPRINT=" + new String(Hex.encode(master.getFingerprint())).toUpperCase());
    }
}
