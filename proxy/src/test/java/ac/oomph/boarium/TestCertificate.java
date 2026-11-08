package ac.oomph.boarium;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * A self-signed certificate for 127.0.0.1, made by the JDK's keytool, as a PKCS12 file and as a PEM certificate with its PEM private key.
 */
public record TestCertificate(Path pkcs12, String password, Path certificate, Path privateKey, X509Certificate x509) {

    public static TestCertificate create(Path folder) throws Exception {
        return create(folder, "-1d");
    }

    /**
     * @param startDate when the certificate's two days of validity start, in keytool's -startdate form, such as -5d for five days ago
     */
    public static TestCertificate create(Path folder, String startDate) throws Exception {
        Path pkcs12 = folder.resolve("certificate.p12");
        String password = "test-password";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-keystore", pkcs12.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-alias", "boarium", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1",
                "-startdate", startDate, "-validity", "2")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore store = KeyStore.getInstance(pkcs12.toFile(), password.toCharArray());
        X509Certificate x509 = (X509Certificate) store.getCertificate("boarium");
        PrivateKey key = (PrivateKey) store.getKey("boarium", password.toCharArray());
        Path certificate = Files.writeString(folder.resolve("certificate.pem"), pem("CERTIFICATE", x509.getEncoded()));
        Path privateKey = Files.writeString(folder.resolve("private-key.pem"), pem("PRIVATE KEY", key.getEncoded()));
        return new TestCertificate(pkcs12, password, certificate, privateKey, x509);
    }

    /**
     * Writes the private key encrypted with {@code password}, as an ENCRYPTED PRIVATE KEY PEM file next to the others.
     */
    public Path encryptedPrivateKey(String password) throws Exception {
        PrivateKey key = (PrivateKey) KeyStore.getInstance(this.pkcs12.toFile(), this.password.toCharArray()).getKey("boarium", this.password.toCharArray());
        String algorithm = "PBEWithSHA1AndDESede";
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password.toCharArray())), new PBEParameterSpec(new byte[8], 2048));
        byte[] encrypted = new EncryptedPrivateKeyInfo(cipher.getParameters(), cipher.doFinal(key.getEncoded())).getEncoded();
        return Files.writeString(this.pkcs12.resolveSibling("encrypted-private-key.pem"), pem("ENCRYPTED PRIVATE KEY", encrypted));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    /**
     * A client's TLS settings that trust this certificate alone.
     */
    public SSLContext trusted() throws Exception {
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("boarium", this.x509);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
