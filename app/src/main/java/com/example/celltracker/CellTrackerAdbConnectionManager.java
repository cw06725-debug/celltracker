package com.example.celltracker;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Calendar;
import javax.security.auth.x500.X500Principal;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** Persistent ADB host identity used by CellTracker. */
public final class CellTrackerAdbConnectionManager extends AbsAdbConnectionManager {
    private static final String ALIAS = "celltracker_adb_host";
    private static CellTrackerAdbConnectionManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized CellTrackerAdbConnectionManager getInstance(Context context) throws Exception {
        if (instance == null) instance = new CellTrackerAdbConnectionManager(context.getApplicationContext());
        return instance;
    }

    private CellTrackerAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance(); end.add(Calendar.YEAR, 20);
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=CellTracker"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(start.getTime()).setCertificateNotAfter(end.getTime()).build());
            gen.generateKeyPair();
        }
        privateKey = (PrivateKey) ks.getKey(ALIAS, null);
        certificate = ks.getCertificate(ALIAS);
        if (privateKey == null || certificate == null) throw new IllegalStateException("ADB host key unavailable");
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "CellTracker"; }
}
