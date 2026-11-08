package ac.oomph.boarium;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;

/**
 * The files NetherNet signaling serves HTTPS with: a PEM certificate chain and its PEM private key, or a PKCS12 file alone when privateKey is null.
 *
 * @param password the PKCS12 file's password, or the password of an encrypted private key; null for none
 */
public record NetherNetHttps(Path certificate, Path privateKey, String password) {
    private static final Logger LOG = LoggerFactory.getLogger(NetherNetHttps.class);

    /**
     * Reads the files, and warns when the certificate is out of its dates.
     *
     * @throws IllegalArgumentException if they cannot be read, or the private key does not belong to the certificate
     */
    public SslContext load() {
        String files = this.certificate + (this.privateKey == null ? "" : " and " + this.privateKey);
        readable(this.certificate);
        if (this.privateKey != null) {
            readable(this.privateKey);
        }
        SslContext tls;
        X509Certificate leaf = null;
        SslContext trustAll;
        try {
            if (this.privateKey == null) {
                char[] password = this.password == null ? new char[0] : this.password.toCharArray();
                KeyStore store = KeyStore.getInstance(this.certificate.toFile(), password);
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store, password);
                tls = SslContextBuilder.forServer(keys).build();
                for (String alias : Collections.list(store.aliases())) {
                    if (store.isKeyEntry(alias)) {
                        leaf = (X509Certificate) store.getCertificate(alias);
                        break;
                    }
                }
            } else {
                tls = SslContextBuilder.forServer(this.certificate.toFile(), this.privateKey.toFile(), this.password).build();
                try (InputStream in = Files.newInputStream(this.certificate)) {
                    leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                }
            }
            trustAll = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot read the NetherNet HTTPS files " + files + ": " + e + this.passwordHint(e), e);
        }
        // Netty takes a private key that does not belong to the certificate, and every HTTPS request would then fail, so a handshake in memory checks the pair now.
        Future<?> handshake = handshake(tls, trustAll);
        if (!handshake.isSuccess()) {
            throw new IllegalArgumentException("the NetherNet HTTPS files " + files + " fail a TLS handshake, as when the private key does not belong to the certificate: " + handshake.cause());
        }
        String outOfDate = leaf == null ? null : outOfDate(leaf, Instant.now());
        if (outOfDate != null) {
            LOG.warn("the NetherNet HTTPS certificate {} {}, so players' games will likely refuse it", this.certificate, outOfDate);
        }
        return tls;
    }

    // Says how the leaf's dates miss now, or null when they do not.
    static String outOfDate(X509Certificate leaf, Instant now) {
        if (now.isBefore(leaf.getNotBefore().toInstant())) {
            return "is not valid until " + leaf.getNotBefore().toInstant();
        }
        if (now.isAfter(leaf.getNotAfter().toInstant())) {
            return "expired at " + leaf.getNotAfter().toInstant();
        }
        return null;
    }

    private static void readable(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException("cannot read the NetherNet HTTPS file " + file);
        }
    }

    // Netty says only that a PEM key is not valid when its password is wrong, missing or not needed.
    private String passwordHint(Exception e) {
        if (this.privateKey == null || !String.valueOf(e.getMessage()).contains("private key")) {
            return "";
        }
        if (this.password != null) {
            return " (check nethernet_password, and leave it empty when the private key is not encrypted)";
        }
        try {
            return Files.readString(this.privateKey, StandardCharsets.ISO_8859_1).contains("ENCRYPTED") ? " (the private key is encrypted, so set nethernet_password)" : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    // Returns the client side's handshake.
    private static Future<?> handshake(SslContext server, SslContext client) {
        EmbeddedChannel serverSide = new EmbeddedChannel(server.newHandler(ByteBufAllocator.DEFAULT));
        EmbeddedChannel clientSide = new EmbeddedChannel(client.newHandler(ByteBufAllocator.DEFAULT));
        Future<?> handshake = clientSide.pipeline().get(SslHandler.class).handshakeFuture();
        try {
            for (int i = 0; i < 10 && !handshake.isDone(); i++) {
                pass(clientSide, serverSide);
                pass(serverSide, clientSide);
            }
        } catch (RuntimeException e) {
            // The handshake holds the failure.
        } finally {
            clientSide.finishAndReleaseAll();
            serverSide.finishAndReleaseAll();
        }
        return handshake;
    }

    private static void pass(EmbeddedChannel from, EmbeddedChannel to) {
        for (ByteBuf data; (data = from.readOutbound()) != null; ) {
            to.writeInbound(data);
        }
    }

    @Override
    public String toString() {
        return "NetherNetHttps[certificate=" + this.certificate + ", privateKey=" + this.privateKey + ", password=" + (this.password == null ? "none" : "***") + "]";
    }
}
