package com.example.celltracker;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import android.sun.security.x509.*;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Persistent ADB host identity.
 * Uses a normal exportable RSA key + X509 cert, matching libadb's reference implementation.
 * Do not use AndroidKeyStore here: ADB pairing/TLS needs a conventional key/certificate pair.
 */
public final class CellTrackerAdbConnectionManager extends AbsAdbConnectionManager {
    private static CellTrackerAdbConnectionManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized CellTrackerAdbConnectionManager getInstance(Context context) throws Exception {
        if (instance == null) instance = new CellTrackerAdbConnectionManager(context.getApplicationContext());
        return instance;
    }

    private CellTrackerAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(context.getFilesDir(), "celltracker_adb_private.pk8");
        File certFile = new File(context.getFilesDir(), "celltracker_adb_cert.der");
        PrivateKey pk = null;
        Certificate cert = null;
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                byte[] kb = readAll(keyFile);
                pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(kb));
                try (InputStream in = new FileInputStream(certFile)) {
                    cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
                }
            } catch (Throwable ignored) {
                keyFile.delete(); certFile.delete(); pk = null; cert = null;
            }
        }
        if (pk == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = gen.generateKeyPair();
            pk = pair.getPrivate();

            String algorithm = "SHA512withRSA";
            X500Name owner = new X500Name("CN=CellTracker");
            Date from = new Date(System.currentTimeMillis() - 60_000L);
            Date to = new Date(System.currentTimeMillis() + 20L * 365 * 24 * 60 * 60 * 1000);
            CertificateValidity interval = new CertificateValidity(from, to);
            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new BigInteger(63, new SecureRandom())));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
            info.set("subject", new CertificateSubjectName(owner));
            info.set("issuer", new CertificateIssuerName(owner));
            info.set("key", new CertificateX509Key(pair.getPublic()));
            info.set("validity", interval);
            CertificateExtensions ext = new CertificateExtensions();
            ext.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                    new KeyIdentifier(pair.getPublic()).getIdentifier()));
            ext.set("PrivateKeyUsage", new PrivateKeyUsageExtension(from, to));
            info.set("extensions", ext);
            X509CertImpl x509 = new X509CertImpl(info);
            x509.sign(pk, algorithm);
            cert = x509;

            try (OutputStream out = new FileOutputStream(keyFile)) { out.write(pk.getEncoded()); }
            try (OutputStream out = new FileOutputStream(certFile)) { out.write(cert.getEncoded()); }
        }
        privateKey = pk;
        certificate = cert;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    @NonNull @Override public PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override public Certificate getCertificate() { return certificate; }
    @NonNull @Override public String getDeviceName() { return "CellTracker"; }
}
