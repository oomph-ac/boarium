package ac.oomph.boarium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetHttpsTest {

    @Test
    void loadsAPemCertificateWithItsKey(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        assertNotNull(new NetherNetHttps(certificate.certificate(), certificate.privateKey(), null).load());
    }

    @Test
    void loadsAPkcs12File(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        assertNotNull(new NetherNetHttps(certificate.pkcs12(), null, certificate.password()).load());
    }

    @Test
    void turnsAwayAPkcs12FileWithTheWrongPassword(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.pkcs12(), null, "wrong").load());
    }

    @Test
    void turnsAwayFilesThatCannotBeRead(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        String error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(dir.resolve("missing.pem"), certificate.privateKey(), null).load()).getMessage();
        assertEquals("cannot read the NetherNet HTTPS file " + dir.resolve("missing.pem"), error);
        error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.certificate(), dir.resolve("missing.pem"), null).load()).getMessage();
        assertEquals("cannot read the NetherNet HTTPS file " + dir.resolve("missing.pem"), error);
        assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(dir, certificate.privateKey(), null).load());
        // A certificate where the private key should be.
        assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.certificate(), certificate.certificate(), null).load());
        Path empty = Files.createFile(dir.resolve("empty.pem"));
        assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(empty, certificate.privateKey(), null).load());
    }

    @Test
    void readsAnEncryptedPrivateKeyWithItsPassword(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        Path encrypted = certificate.encryptedPrivateKey("key-password");
        assertNotNull(new NetherNetHttps(certificate.certificate(), encrypted, "key-password").load());

        String error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.certificate(), encrypted, null).load()).getMessage();
        assertTrue(error.endsWith("(the private key is encrypted, so set nethernet_password)"), error);
        error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.certificate(), encrypted, "wrong").load()).getMessage();
        assertTrue(error.endsWith("(check nethernet_password, and leave it empty when the private key is not encrypted)"), error);
        assertFalse(error.contains("wrong"), error);
    }

    @Test
    void hintsAtThePasswordWhenOneIsSetForAKeyThatIsNotEncrypted(@TempDir Path dir) throws Exception {
        TestCertificate certificate = TestCertificate.create(dir);
        String error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(certificate.certificate(), certificate.privateKey(), "secret").load()).getMessage();
        assertTrue(error.endsWith("(check nethernet_password, and leave it empty when the private key is not encrypted)"), error);
    }

    // Out of its dates, a certificate still loads; boarium only warns.
    @Test
    void loadsACertificateOutOfItsDates(@TempDir Path dir) throws Exception {
        TestCertificate expired = TestCertificate.create(Files.createDirectory(dir.resolve("expired")), "-5d");
        TestCertificate early = TestCertificate.create(Files.createDirectory(dir.resolve("early")), "+5d");
        assertNotNull(new NetherNetHttps(expired.certificate(), expired.privateKey(), null).load());
        assertNotNull(new NetherNetHttps(early.pkcs12(), null, early.password()).load());

        Instant now = Instant.now();
        assertTrue(NetherNetHttps.outOfDate(expired.x509(), now).startsWith("expired at "));
        assertTrue(NetherNetHttps.outOfDate(early.x509(), now).startsWith("is not valid until "));
        assertNull(NetherNetHttps.outOfDate(TestCertificate.create(dir).x509(), now));
    }

    @Test
    void keepsThePasswordOutOfToString() {
        NetherNetHttps https = new NetherNetHttps(Path.of("cert.p12"), null, "hunter2");
        assertFalse(https.toString().contains("hunter2"), https.toString());
        assertTrue(https.toString().contains("cert.p12"), https.toString());
    }

    @Test
    void turnsAwayAPrivateKeyOfAnotherCertificate(@TempDir Path dir) throws Exception {
        TestCertificate one = TestCertificate.create(Files.createDirectory(dir.resolve("one")));
        TestCertificate two = TestCertificate.create(Files.createDirectory(dir.resolve("two")));
        String error = assertThrows(IllegalArgumentException.class, () -> new NetherNetHttps(one.certificate(), two.privateKey(), null).load()).getMessage();
        assertTrue(error.contains("handshake"), error);
    }
}
