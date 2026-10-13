package com.zetteldraw.penpoc.sync;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;

/**
 * Package signing fingerprint helpers for Google OAuth diagnostics.
 * Tip / debug sideloads must match the SHA-1 registered on the Android OAuth client.
 */
public final class SigningCert {
    /** Historical tip/debug keystore SHA-1 (committed {@code app/debug.keystore}). */
    public static final String TIP_DEBUG_SHA1 =
            "99:0A:D2:BC:14:2A:E3:61:3D:08:CC:B7:2D:6F:AA:C7:A7:0B:C0:73";

    private SigningCert() {
    }

    /** Colon-separated uppercase SHA-1 of the APK signing cert, or empty if unknown. */
    public static String sha1(Context context) {
        try {
            byte[] cert = firstSigningCert(context);
            if (cert == null) {
                return "";
            }
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(cert);
            StringBuilder sb = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] firstSigningCert(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                return null;
            }
            Signature[] sigs = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) {
                return null;
            }
            return sigs[0].toByteArray();
        }
        @SuppressWarnings("deprecation")
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
        @SuppressWarnings("deprecation")
        Signature[] sigs = info.signatures;
        if (sigs == null || sigs.length == 0) {
            return null;
        }
        return sigs[0].toByteArray();
    }
}
