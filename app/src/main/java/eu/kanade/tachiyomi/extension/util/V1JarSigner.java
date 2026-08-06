package eu.kanade.tachiyomi.extension.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipFile;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Generates a JAR (APK Signature Scheme v1) signature for an extension APK using
 * only plain JDK/Android APIs (MessageDigest, Signature, CertificateFactory).
 *
 * Android 6 (API 23) can only read v1 signatures, and every modern signing
 * library fails on it:
 *  - apksig 3.x+ calls Class.getDeclaredAnnotation (added in API 26)
 *  - apksig 2.x relies on sun.security.* classes that are not present at runtime
 * This implementation hand-rolls the PKCS#7 SignedData block (the hard part) and
 * the JAR manifest files, which is exactly what `jarsigner` produces.
 */
public final class V1JarSigner {

    private static final String SIGNER_NAME = "EXTENSIO";

    private static final byte[] OID_SHA256 = oid(2, 16, 840, 1, 101, 3, 4, 2, 1);
    // sha256WithRSAEncryption: MUST be the full digest+encryption OID, not plain
    // rsaEncryption, so Android 6 resolves Signature.getInstance(deaOid) to
    // SHA256withRSA (PKCS#1 padded).
    private static final byte[] OID_SHA256_WITH_RSA = oid(1, 2, 840, 113549, 1, 1, 11);

    private static final byte[] OID_DATA = oid(1, 2, 840, 113549, 1, 7, 1);
    private static final byte[] OID_SIGNED_DATA = oid(1, 2, 840, 113549, 1, 7, 2);
    private static final byte[] OID_ATTR_CONTENT_TYPE = oid(1, 2, 840, 113549, 1, 9, 3);
    private static final byte[] OID_ATTR_MESSAGE_DIGEST = oid(1, 2, 840, 113549, 1, 9, 4);
    private static final byte[] OID_ATTR_SIGNING_TIME = oid(1, 2, 840, 113549, 1, 9, 5);

    private static final byte[] DER_NULL = der(0x05, new byte[0]);

    private static final char[] B64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private V1JarSigner() {}

    /**
     * Re-signs [input] APK with the v1 (JAR) scheme using the given identity,
     * writing the result to [output]. The output has no APK Signing Block (v2),
     * which is exactly what Android 6 needs.
     */
    public static void sign(File input, File output, PrivateKey key, List<X509Certificate> chain) throws Exception {
        // 1. Read all original entries (drop old META-INF signature files).
        // Read with ZipFile (central directory), NOT ZipInputStream: some legacy
        // extension APKs have bytes before the first local file header, which
        // Android 6's ZipInputStream reports as a phantom entry and throws
        // "Entry is not named". ZipFile skips such prefixes and returns only the
        // real entries. Directory entries and empty names are dropped: Android 6's
        // StrictJarFile rejects any MANIFEST section whose file does not exist as
        // a zip entry ("File  in manifest does not exist").
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        Map<String, Integer> methods = new LinkedHashMap<String, Integer>();
        try (ZipFile zipFile = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> en = zipFile.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.isEmpty() || e.isDirectory() || name.startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = zipFile.getInputStream(e)) {
                    entries.put(name, readAll(in));
                }
                methods.put(name, e.getMethod());
            }
        }

        // 2. MANIFEST.MF: digest of every entry.
        StringBuilder mf = new StringBuilder();
        mf.append("Manifest-Version: 1.0\r\n");
        mf.append("Created-By: 1.0 (Android)\r\n\r\n");
        Map<String, String> sections = new LinkedHashMap<String, String>();
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String section = "Name: " + en.getKey() + "\r\n"
                + "SHA-256-Digest: " + b64(sha256(en.getValue())) + "\r\n\r\n";
            mf.append(section);
            sections.put(en.getKey(), section);
        }
        byte[] mfBytes = mf.toString().getBytes(StandardCharsets.UTF_8);

        // 3. EXTENSIO.SF: digest of the manifest plus per-section digests.
        StringBuilder sf = new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(b64(sha256(mfBytes))).append("\r\n");
        sf.append("Created-By: 1.0 (Android)\r\n\r\n");
        for (String name : entries.keySet()) {
            sf.append("Name: ").append(name).append("\r\n");
            sf.append("SHA-256-Digest: ")
                .append(b64(sha256(sections.get(name).getBytes(StandardCharsets.UTF_8))))
                .append("\r\n\r\n");
        }
        byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

        // 4. EXTENSIO.RSA: PKCS#7 SignedData over the .SF.
        byte[] rsaBytes = buildPkcs7(sfBytes, key, chain);

        // 5. Write the output zip: signatures first, then original entries.
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(output))) {
            writeEntry(zout, "META-INF/MANIFEST.MF", mfBytes, ZipEntry.DEFLATED);
            writeEntry(zout, "META-INF/" + SIGNER_NAME + ".SF", sfBytes, ZipEntry.DEFLATED);
            writeEntry(zout, "META-INF/" + SIGNER_NAME + ".RSA", rsaBytes, ZipEntry.DEFLATED);
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                writeEntry(zout, en.getKey(), en.getValue(), methods.get(en.getKey()));
            }
        }
    }

    // ---------------------------------------------------------------- PKCS#7

    private static byte[] buildPkcs7(byte[] content, PrivateKey key, List<X509Certificate> chain) throws Exception {
        X509Certificate cert = chain.get(0);

        byte[] digestAlgId = der(0x30, OID_SHA256, DER_NULL);
        byte[] digestAlgs = der(0x31, digestAlgId); // SET OF AlgorithmIdentifier

        // encapContentInfo: data OID WITHOUT the content (detached signature,
        // exactly like jarsigner output, which the JDK 7/8-era verifier used by
        // Android 6 accepts; it checks the .SF against the messageDigest
        // authenticated attribute below).
        byte[] contentInfo = der(0x30, OID_DATA);

        // certificates [0] IMPLICIT SET OF Certificate
        byte[] certBytes = new byte[0];
        for (X509Certificate c : chain) {
            certBytes = concat(certBytes, c.getEncoded());
        }
        byte[] certificates = der(0xA0, certBytes);

        // Authenticated attributes (exactly like jarsigner): contentType,
        // messageDigest of the .SF and signingTime.
        byte[] attrContentType = der(0x30, OID_ATTR_CONTENT_TYPE, der(0x31, OID_DATA));
        byte[] attrMessageDigest = der(0x30, OID_ATTR_MESSAGE_DIGEST, der(0x31, der(0x04, sha256(content))));
        byte[] attrSigningTime = der(0x30, OID_ATTR_SIGNING_TIME, der(0x31, utcTime()));
        // The [0] IMPLICIT wrapper goes in the signerInfo field...
        byte[] attrs = der(0xA0, attrContentType, attrMessageDigest, attrSigningTime);
        // ...but the signature covers the plain SET encoding (0x31), which is
        // exactly what Android 6 re-encodes for verification
        // (AuthenticatedAttributes.ASN1 is an ASN1SetOf, "non-IMPLICIT").
        byte[] signedAttrs = der(0x31, attrContentType, attrMessageDigest, attrSigningTime);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signedAttrs);
        byte[] signature = sig.sign();

        byte[] issuerAndSerial = der(0x30,
            cert.getIssuerX500Principal().getEncoded(),
            der(0x02, cert.getSerialNumber().toByteArray()));
        byte[] encAlg = der(0x30, OID_SHA256_WITH_RSA, DER_NULL);

        byte[] signerInfo = der(0x30,
            der(0x02, new byte[] {1}), // version
            issuerAndSerial,
            digestAlgId,
            attrs,
            encAlg,
            der(0x04, signature));

        byte[] signerInfos = der(0x31, signerInfo); // SET OF SignerInfo

        byte[] signedData = der(0x30,
            der(0x02, new byte[] {1}), // version
            digestAlgs,
            contentInfo,
            certificates,
            signerInfos);

        // Outer ContentInfo: signedData OID + [0] EXPLICIT SignedData
        return der(0x30, OID_SIGNED_DATA, der(0xA0, signedData));
    }

    // ------------------------------------------------------------------ DER

    private static byte[] der(int tag, byte[]... parts) {
        return der(tag, concat(parts));
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 5);
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = 0;
            for (int t = len; t > 0; t >>>= 8) bytes++;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((len >>> (i * 8)) & 0xFF);
            }
        }
    }

    /** Encodes a dotted OID as a full OBJECT IDENTIFIER TLV (first two arcs combined, base-128). */
    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            // base-128, big endian, high bit set on all but the last byte
            int[] stack = new int[5];
            int n = 0;
            stack[n++] = v & 0x7F;
            while ((v >>= 7) > 0) {
                stack[n++] = (v & 0x7F) | 0x80;
            }
            for (int j = n - 1; j >= 0; j--) {
                out.write(stack[j]);
            }
        }
        return der(0x06, out.toByteArray());
    }

    private static byte[] utcTime() {
        java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] content = fmt.format(new java.util.Date()).getBytes(StandardCharsets.US_ASCII);
        return der(0x17, content); // UTCTime
    }

    // ------------------------------------------------------------- utilities

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static void writeEntry(ZipOutputStream zout, String name, byte[] data, int method)
        throws IOException {
        ZipEntry ze = new ZipEntry(name);
        if (method == ZipEntry.STORED) {
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            ze.setCrc(crc.getValue());
        }
        zout.putNextEntry(ze);
        zout.write(data);
        zout.closeEntry();
    }

    private static String b64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(B64_ALPHABET[(n >>> 18) & 63])
                .append(B64_ALPHABET[(n >>> 12) & 63])
                .append(B64_ALPHABET[(n >>> 6) & 63])
                .append(B64_ALPHABET[n & 63]);
        }
        if (i + 1 == data.length) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(B64_ALPHABET[(n >>> 18) & 63])
                .append(B64_ALPHABET[(n >>> 12) & 63])
                .append("==");
        } else if (i + 2 == data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64_ALPHABET[(n >>> 18) & 63])
                .append(B64_ALPHABET[(n >>> 12) & 63])
                .append(B64_ALPHABET[(n >>> 6) & 63])
                .append('=');
        }
        return sb.toString();
    }
}
