/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.SystemApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.android.internal.logging.MetricsLogger;

import sun.security.pkcs.PKCS7;
import sun.security.pkcs.SignerInfo;

/**
 * RecoverySystem contains methods for interacting with the Android
 * recovery system (the separate partition that can be used to install
 * system updates, wipe user data, etc.)
 */
public class RecoverySystem {
    private static final String TAG = "RecoverySystem";

    /**
     * Default location of zip file containing public keys (X509
     * certs) authorized to sign OTA updates.
     */
    private static final File DEFAULT_KEYSTORE =
        new File("/system/etc/security/otacerts.zip");

    /** Send progress to listeners no more often than this (in ms). */
    private static final long PUBLISH_PROGRESS_INTERVAL_MS = 500;

    /** Used to communicate with recovery.  See bootable/recovery/recovery.cpp. */
    private static final File RECOVERY_DIR = new File("/cache/recovery");
    private static final File LOG_FILE = new File(RECOVERY_DIR, "log");
    private static final File LAST_INSTALL_FILE = new File(RECOVERY_DIR, "last_install");
    private static final String LAST_PREFIX = "last_";

    /**
     * The recovery image uses this file to identify the location (i.e. blocks)
     * of an OTA package on the /data partition. The block map file is
     * generated by uncrypt.
     *
     * @hide
     */
    public static final File BLOCK_MAP_FILE = new File(RECOVERY_DIR, "block.map");

    /**
     * UNCRYPT_PACKAGE_FILE stores the filename to be uncrypt'd, which will be
     * read by uncrypt.
     *
     * @hide
     */
    public static final File UNCRYPT_PACKAGE_FILE = new File(RECOVERY_DIR, "uncrypt_file");

    /**
     * UNCRYPT_STATUS_FILE stores the time cost (and error code in the case of a failure)
     * of uncrypt.
     *
     * @hide
     */
    public static final File UNCRYPT_STATUS_FILE = new File(RECOVERY_DIR, "uncrypt_status");

    // Length limits for reading files.
    private static final int LOG_FILE_MAX_LENGTH = 64 * 1024;

    // Prevent concurrent execution of requests.
    private static final Object sRequestLock = new Object();

    private final IRecoverySystem mService;

    /**
     * Interface definition for a callback to be invoked regularly as
     * verification proceeds.
     */
    public interface ProgressListener {
        /**
         * Called periodically as the verification progresses.
         *
         * @param progress  the approximate percentage of the
         *        verification that has been completed, ranging from 0
         *        to 100 (inclusive).
         */
        public void onProgress(int progress);
    }

    /** @return the set of certs that can be used to sign an OTA package. */
    private static HashSet<X509Certificate> getTrustedCerts(File keystore)
        throws IOException, GeneralSecurityException {
        HashSet<X509Certificate> trusted = new HashSet<X509Certificate>();
        if (keystore == null) {
            keystore = DEFAULT_KEYSTORE;
        }
        ZipFile zip = new ZipFile(keystore);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                InputStream is = zip.getInputStream(entry);
                try {
                    trusted.add((X509Certificate) cf.generateCertificate(is));
                } finally {
                    is.close();
                }
            }
        } finally {
            zip.close();
        }
        return trusted;
    }

    /**
     * Verify the cryptographic signature of a system update package
     * before installing it.  Note that the package is also verified
     * separately by the installer once the device is rebooted into
     * the recovery system.  This function will return only if the
     * package was successfully verified; otherwise it will throw an
     * exception.
     *
     * Verification of a package can take significant time, so this
     * function should not be called from a UI thread.  Interrupting
     * the thread while this function is in progress will result in a
     * SecurityException being thrown (and the thread's interrupt flag
     * will be cleared).
     *
     * @param packageFile  the package to be verified
     * @param listener     an object to receive periodic progress
     * updates as verification proceeds.  May be null.
     * @param deviceCertsZipFile  the zip file of certificates whose
     * public keys we will accept.  Verification succeeds if the
     * package is signed by the private key corresponding to any
     * public key in this file.  May be null to use the system default
     * file (currently "/system/etc/security/otacerts.zip").
     *
     * @throws IOException if there were any errors reading the
     * package or certs files.
     * @throws GeneralSecurityException if verification failed
     */
    public static void verifyPackage(File packageFile,
                                     ProgressListener listener,
                                     File deviceCertsZipFile)
        throws IOException, GeneralSecurityException {
        final long fileLen = packageFile.length();

        final RandomAccessFile raf = new RandomAccessFile(packageFile, "r");
        try {
            final long startTimeMillis = System.currentTimeMillis();
            if (listener != null) {
                listener.onProgress(0);
            }

            raf.seek(fileLen - 6);
            byte[] footer = new byte[6];
            raf.readFully(footer);

            if (footer[2] != (byte)0xff || footer[3] != (byte)0xff) {
                throw new SignatureException("no signature in file (no footer)");
            }

            final int commentSize = (footer[4] & 0xff) | ((footer[5] & 0xff) << 8);
            final int signatureStart = (footer[0] & 0xff) | ((footer[1] & 0xff) << 8);

            byte[] eocd = new byte[commentSize + 22];
            raf.seek(fileLen - (commentSize + 22));
            raf.readFully(eocd);

            // Check that we have found the start of the
            // end-of-central-directory record.
            if (eocd[0] != (byte)0x50 || eocd[1] != (byte)0x4b ||
                eocd[2] != (byte)0x05 || eocd[3] != (byte)0x06) {
                throw new SignatureException("no signature in file (bad footer)");
            }

            for (int i = 4; i < eocd.length-3; ++i) {
                if (eocd[i  ] == (byte)0x50 && eocd[i+1] == (byte)0x4b &&
                    eocd[i+2] == (byte)0x05 && eocd[i+3] == (byte)0x06) {
                    throw new SignatureException("EOCD marker found after start of EOCD");
                }
            }

            // Parse the signature
            PKCS7 block =
                new PKCS7(new ByteArrayInputStream(eocd, commentSize+22-signatureStart, signatureStart));

            // Take the first certificate from the signature (packages
            // should contain only one).
            X509Certificate[] certificates = block.getCertificates();
            if (certificates == null || certificates.length == 0) {
                throw new SignatureException("signature contains no certificates");
            }
            X509Certificate cert = certificates[0];
            PublicKey signatureKey = cert.getPublicKey();

            SignerInfo[] signerInfos = block.getSignerInfos();
            if (signerInfos == null || signerInfos.length == 0) {
                throw new SignatureException("signature contains no signedData");
            }
            SignerInfo signerInfo = signerInfos[0];

            // Check that the public key of the certificate contained
            // in the package equals one of our trusted public keys.
            boolean verified = false;
            HashSet<X509Certificate> trusted = getTrustedCerts(
                deviceCertsZipFile == null ? DEFAULT_KEYSTORE : deviceCertsZipFile);
            for (X509Certificate c : trusted) {
                if (c.getPublicKey().equals(signatureKey)) {
                    verified = true;
                    break;
                }
            }
            if (!verified) {
                throw new SignatureException("signature doesn't match any trusted key");
            }

            // The signature cert matches a trusted key.  Now verify that
            // the digest in the cert matches the actual file data.
            raf.seek(0);
            final ProgressListener listenerForInner = listener;
            SignerInfo verifyResult = block.verify(signerInfo, new InputStream() {
                // The signature covers all of the OTA package except the
                // archive comment and its 2-byte length.
                long toRead = fileLen - commentSize - 2;
                long soFar = 0;

                int lastPercent = 0;
                long lastPublishTime = startTimeMillis;

                @Override
                public int read() throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (soFar >= toRead) {
                        return -1;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return -1;
                    }

                    int size = len;
                    if (soFar + size > toRead) {
                        size = (int)(toRead - soFar);
                    }
                    int read = raf.read(b, off, size);
                    soFar += read;

                    if (listenerForInner != null) {
                        long now = System.currentTimeMillis();
                        int p = (int)(soFar * 100 / toRead);
                        if (p > lastPercent &&
                            now - lastPublishTime > PUBLISH_PROGRESS_INTERVAL_MS) {
                            lastPercent = p;
                            lastPublishTime = now;
                            listenerForInner.onProgress(lastPercent);
                        }
                    }

                    return read;
                }
            });

            final boolean interrupted = Thread.interrupted();
            if (listener != null) {
                listener.onProgress(100);
            }

            if (interrupted) {
                throw new SignatureException("verification was interrupted");
            }

            if (verifyResult == null) {
                throw new SignatureException("signature digest verification failed");
            }
        } finally {
            raf.close();
        }
    }

    /**
     * Process a given package with uncrypt. No-op if the package is not on the
     * /data partition.
     *
     * @param Context      the Context to use
     * @param packageFile  the package to be processed
     * @param listener     an object to receive periodic progress updates as
     *                     processing proceeds.  May be null.
     * @param handler      the Handler upon which the callbacks will be
     *                     executed.
     *
     * @throws IOException if there were any errors processing the package file.
     *
     * @hide
     */
    @SystemApi
    public static void processPackage(Context context,
                                      File packageFile,
                                      final ProgressListener listener,
                                      final Handler handler)
            throws IOException {
        String filename = packageFile.getCanonicalPath();
        if (!filename.startsWith("/data/")) {
            return;
        }

        RecoverySystem rs = (RecoverySystem) context.getSystemService(Context.RECOVERY_SERVICE);
        IRecoverySystemProgressListener progressListener = null;
        if (listener != null) {
            final Handler progressHandler;
            if (handler != null) {
                progressHandler = handler;
            } else {
                progressHandler = new Handler(context.getMainLooper());
            }
            progressListener = new IRecoverySystemProgressListener.Stub() {
                int lastProgress = 0;
                long lastPublishTime = System.currentTimeMillis();

                @Override
                public void onProgress(final int progress) {
                    final long now = System.currentTimeMillis();
                    progressHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (progress > lastProgress &&
                                    now - lastPublishTime > PUBLISH_PROGRESS_INTERVAL_MS) {
                                lastProgress = progress;
                                lastPublishTime = now;
                                listener.onProgress(progress);
                            }
                        }
                    });
                }
            };
        }

        if (!rs.uncrypt(filename, progressListener)) {
            throw new IOException("process package failed");
        }
    }

    /**
     * Process a given package with uncrypt. No-op if the package is not on the
     * /data partition.
     *
     * @param Context      the Context to use
     * @param packageFile  the package to be processed
     * @param listener     an object to receive periodic progress updates as
     *                     processing proceeds.  May be null.
     *
     * @throws IOException if there were any errors processing the package file.
     *
     * @hide
     */
    @SystemApi
    public static void processPackage(Context context,
                                      File packageFile,
                                      final ProgressListener listener)
            throws IOException {
        processPackage(context, packageFile, listener, null);
    }

    /**
     * Reboots the device in order to install the given update
     * package.
     * Requires the {@link android.Manifest.permission#REBOOT} permission.
     *
     * @param context      the Context to use
     * @param packageFile  the update package to install.  Must be on
     * a partition mountable by recovery.  (The set of partitions
     * known to recovery may vary from device to device.  Generally,
     * /cache and /data are safe.)
     *
     * @throws IOException  if writing the recovery command file
     * fails, or if the reboot itself fails.
     */
    public static void installPackage(Context context, File packageFile)
            throws IOException {
        installPackage(context, packageFile, false);
    }

    /**
     * If the package hasn't been processed (i.e. uncrypt'd), set up
     * UNCRYPT_PACKAGE_FILE and delete BLOCK_MAP_FILE to trigger uncrypt during the
     * reboot.
     *
     * @param context      the Context to use
     * @param packageFile  the update package to install.  Must be on a
     * partition mountable by recovery.
     * @param processed    if the package has been processed (uncrypt'd).
     *
     * @throws IOException if writing the recovery command file fails, or if
     * the reboot itself fails.
     *
     * @hide
     */
    @SystemApi
    public static void installPackage(Context context, File packageFile, boolean processed)
            throws IOException {
        synchronized (sRequestLock) {
            LOG_FILE.delete();
            // Must delete the file in case it was created by system server.
            UNCRYPT_PACKAGE_FILE.delete();

            String filename = packageFile.getCanonicalPath();
            Log.w(TAG, "!!! REBOOTING TO INSTALL " + filename + " !!!");

            // If the package name ends with "_s.zip", it's a security update.
            boolean securityUpdate = filename.endsWith("_s.zip");

            // If the package is on the /data partition, the package needs to
            // be processed (i.e. uncrypt'd). The caller specifies if that has
            // been done in 'processed' parameter.
            if (filename.startsWith("/data/")) {
                if (processed) {
                    if (!BLOCK_MAP_FILE.exists()) {
                        Log.e(TAG, "Package claimed to have been processed but failed to find "
                                + "the block map file.");
                        throw new IOException("Failed to find block map file");
                    }
                } else {
                    FileWriter uncryptFile = new FileWriter(UNCRYPT_PACKAGE_FILE);
                    try {
                        uncryptFile.write(filename + "\n");
                    } finally {
                        uncryptFile.close();
                    }
                    // UNCRYPT_PACKAGE_FILE needs to be readable and writable
                    // by system server.
                    if (!UNCRYPT_PACKAGE_FILE.setReadable(true, false)
                            || !UNCRYPT_PACKAGE_FILE.setWritable(true, false)) {
                        Log.e(TAG, "Error setting permission for " + UNCRYPT_PACKAGE_FILE);
                    }

                    BLOCK_MAP_FILE.delete();
                }

                // If the package is on the /data partition, use the block map
                // file as the package name instead.
                filename = "@/cache/recovery/block.map";
            }

            final String filenameArg = "--update_package=" + filename + "\n";
            final String localeArg = "--locale=" + Locale.getDefault().toString() + "\n";
            final String securityArg = "--security\n";

            String command = filenameArg + localeArg;
            if (securityUpdate) {
                command += securityArg;
            }

            RecoverySystem rs = (RecoverySystem) context.getSystemService(
                    Context.RECOVERY_SERVICE);
            if (!rs.setupBcb(command)) {
                throw new IOException("Setup BCB failed");
            }

            // Having set up the BCB (bootloader control block), go ahead and reboot
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            pm.reboot(PowerManager.REBOOT_RECOVERY_UPDATE);

            throw new IOException("Reboot failed (no permissions?)");
        }
    }

    /**
     * Schedule to install the given package on next boot. The caller needs to
     * ensure that the package must have been processed (uncrypt'd) if needed.
     * It sets up the command in BCB (bootloader control block), which will
     * be read by the bootloader and the recovery image.
     *
     * @param Context      the Context to use.
     * @param packageFile  the package to be installed.
     *
     * @throws IOException if there were any errors setting up the BCB.
     *
     * @hide
     */
    @SystemApi
    public static void scheduleUpdateOnBoot(Context context, File packageFile)
            throws IOException {
        String filename = packageFile.getCanonicalPath();
        boolean securityUpdate = filename.endsWith("_s.zip");

        // If the package is on the /data partition, use the block map file as
        // the package name instead.
        if (filename.startsWith("/data/")) {
            filename = "@/cache/recovery/block.map";
        }

        final String filenameArg = "--update_package=" + filename + "\n";
        final String localeArg = "--locale=" + Locale.getDefault().toString() + "\n";
        final String securityArg = "--security\n";

        String command = filenameArg + localeArg;
        if (securityUpdate) {
            command += securityArg;
        }

        RecoverySystem rs = (RecoverySystem) context.getSystemService(Context.RECOVERY_SERVICE);
        if (!rs.setupBcb(command)) {
            throw new IOException("schedule update on boot failed");
        }
    }

    /**
     * Cancel any scheduled update by clearing up the BCB (bootloader control
     * block).
     *
     * @param Context      the Context to use.
     *
     * @throws IOException if there were any errors clearing up the BCB.
     *
     * @hide
     */
    @SystemApi
    public static void cancelScheduledUpdate(Context context)
            throws IOException {
        RecoverySystem rs = (RecoverySystem) context.getSystemService(Context.RECOVERY_SERVICE);
        if (!rs.clearBcb()) {
            throw new IOException("cancel scheduled update failed");
        }
    }

    /**
     * Reboots the device and wipes the user data and cache
     * partitions.  This is sometimes called a "factory reset", which
     * is something of a misnomer because the system partition is not
     * restored to its factory state.  Requires the
     * {@link android.Manifest.permission#REBOOT} permission.
     *
     * @param context  the Context to use
     *
     * @throws IOException  if writing the recovery command file
     * fails, or if the reboot itself fails.
     * @throws SecurityException if the current user is not allowed to wipe data.
     */
    public static void rebootWipeUserData(Context context) throws IOException {
        rebootWipeUserData(context, false /* shutdown */, context.getPackageName(),
                false /* force */);
    }

    /** {@hide} */
    public static void rebootWipeUserData(Context context, String reason) throws IOException {
        rebootWipeUserData(context, false /* shutdown */, reason, false /* force */);
    }

    /** {@hide} */
    public static void rebootWipeUserData(Context context, boolean shutdown)
            throws IOException {
        rebootWipeUserData(context, shutdown, context.getPackageName(), false /* force */);
    }

    /**
     * Reboots the device and wipes the user data and cache
     * partitions.  This is sometimes called a "factory reset", which
     * is something of a misnomer because the system partition is not
     * restored to its factory state.  Requires the
     * {@link android.Manifest.permission#REBOOT} permission.
     *
     * @param context   the Context to use
     * @param shutdown  if true, the device will be powered down after
     *                  the wipe completes, rather than being rebooted
     *                  back to the regular system.
     * @param reason    the reason for the wipe that is visible in the logs
     * @param force     whether the {@link UserManager.DISALLOW_FACTORY_RESET} user restriction
     *                  should be ignored
     *
     * @throws IOException  if writing the recovery command file
     * fails, or if the reboot itself fails.
     * @throws SecurityException if the current user is not allowed to wipe data.
     *
     * @hide
     */
    public static void rebootWipeUserData(Context context, boolean shutdown, String reason,
            boolean force) throws IOException {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (!force && um.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)) {
            throw new SecurityException("Wiping data is not allowed for this user.");
        }
        final ConditionVariable condition = new ConditionVariable();

        Intent intent = new Intent("android.intent.action.MASTER_CLEAR_NOTIFICATION");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendOrderedBroadcastAsUser(intent, UserHandle.SYSTEM,
                android.Manifest.permission.MASTER_CLEAR,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        condition.open();
                    }
                }, null, 0, null, null);

        // Block until the ordered broadcast has completed.
        condition.block();

        String shutdownArg = null;
        if (shutdown) {
            shutdownArg = "--shutdown_after";
        }

        String reasonArg = null;
        if (!TextUtils.isEmpty(reason)) {
            reasonArg = "--reason=" + sanitizeArg(reason);
        }

        final String localeArg = "--locale=" + Locale.getDefault().toString();
        bootCommand(context, shutdownArg, "--wipe_data", reasonArg, localeArg);
    }

    /**
     * Reboot into the recovery system to wipe the /cache partition.
     * @throws IOException if something goes wrong.
     */
    public static void rebootWipeCache(Context context) throws IOException {
        rebootWipeCache(context, context.getPackageName());
    }

    /** {@hide} */
    public static void rebootWipeCache(Context context, String reason) throws IOException {
        String reasonArg = null;
        if (!TextUtils.isEmpty(reason)) {
            reasonArg = "--reason=" + sanitizeArg(reason);
        }

        final String localeArg = "--locale=" + Locale.getDefault().toString();
        bootCommand(context, "--wipe_cache", reasonArg, localeArg);
    }

    /**
     * Reboot into recovery and wipe the A/B device.
     *
     * @param Context      the Context to use.
     * @param packageFile  the wipe package to be applied.
     * @param reason       the reason to wipe.
     *
     * @throws IOException if something goes wrong.
     *
     * @hide
     */
    @SystemApi
    public static void rebootWipeAb(Context context, File packageFile, String reason)
            throws IOException {
        String reasonArg = null;
        if (!TextUtils.isEmpty(reason)) {
            reasonArg = "--reason=" + sanitizeArg(reason);
        }

        final String filename = packageFile.getCanonicalPath();
        final String filenameArg = "--wipe_package=" + filename;
        final String localeArg = "--locale=" + Locale.getDefault().toString();
        bootCommand(context, "--wipe_ab", filenameArg, reasonArg, localeArg);
    }

    /**
     * Reboot into the recovery system with the supplied argument.
     * @param args to pass to the recovery utility.
     * @throws IOException if something goes wrong.
     */
    private static void bootCommand(Context context, String... args) throws IOException {
        LOG_FILE.delete();

        StringBuilder command = new StringBuilder();
        for (String arg : args) {
            if (!TextUtils.isEmpty(arg)) {
                command.append(arg);
                command.append("\n");
            }
        }

        // Write the command into BCB (bootloader control block) and boot from
        // there. Will not return unless failed.
        RecoverySystem rs = (RecoverySystem) context.getSystemService(Context.RECOVERY_SERVICE);
        rs.rebootRecoveryWithCommand(command.toString());

        throw new IOException("Reboot failed (no permissions?)");
    }

    // Read last_install; then report time (in seconds) and I/O (in MiB) for
    // this update to tron.
    // Only report on the reboots immediately after an OTA update.
    private static void parseLastInstallLog(Context context) {
        try (BufferedReader in = new BufferedReader(new FileReader(LAST_INSTALL_FILE))) {
            String line = null;
            int bytesWrittenInMiB = -1, bytesStashedInMiB = -1;
            int timeTotal = -1;
            int uncryptTime = -1;
            int sourceVersion = -1;
            int temperature_start = -1;
            int temperature_end = -1;
            int temperature_max = -1;

            while ((line = in.readLine()) != null) {
                // Here is an example of lines in last_install:
                // ...
                // time_total: 101
                // bytes_written_vendor: 51074
                // bytes_stashed_vendor: 200
                int numIndex = line.indexOf(':');
                if (numIndex == -1 || numIndex + 1 >= line.length()) {
                    continue;
                }
                String numString = line.substring(numIndex + 1).trim();
                long parsedNum;
                try {
                    parsedNum = Long.parseLong(numString);
                } catch (NumberFormatException ignored) {
                    Log.e(TAG, "Failed to parse numbers in " + line);
                    continue;
                }

                final int MiB = 1024 * 1024;
                int scaled;
                try {
                    if (line.startsWith("bytes")) {
                        scaled = Math.toIntExact(parsedNum / MiB);
                    } else {
                        scaled = Math.toIntExact(parsedNum);
                    }
                } catch (ArithmeticException ignored) {
                    Log.e(TAG, "Number overflows in " + line);
                    continue;
                }

                if (line.startsWith("time")) {
                    timeTotal = scaled;
                } else if (line.startsWith("uncrypt_time")) {
                    uncryptTime = scaled;
                } else if (line.startsWith("source_build")) {
                    sourceVersion = scaled;
                } else if (line.startsWith("bytes_written")) {
                    bytesWrittenInMiB = (bytesWrittenInMiB == -1) ? scaled :
                            bytesWrittenInMiB + scaled;
                } else if (line.startsWith("bytes_stashed")) {
                    bytesStashedInMiB = (bytesStashedInMiB == -1) ? scaled :
                            bytesStashedInMiB + scaled;
                } else if (line.startsWith("temperature_start")) {
                    temperature_start = scaled;
                } else if (line.startsWith("temperature_end")) {
                    temperature_end = scaled;
                } else if (line.startsWith("temperature_max")) {
                    temperature_max = scaled;
                }
            }

            // Don't report data to tron if corresponding entry isn't found in last_install.
            if (timeTotal != -1) {
                MetricsLogger.histogram(context, "ota_time_total", timeTotal);
            }
            if (uncryptTime != -1) {
                MetricsLogger.histogram(context, "ota_uncrypt_time", uncryptTime);
            }
            if (sourceVersion != -1) {
                MetricsLogger.histogram(context, "ota_source_version", sourceVersion);
            }
            if (bytesWrittenInMiB != -1) {
                MetricsLogger.histogram(context, "ota_written_in_MiBs", bytesWrittenInMiB);
            }
            if (bytesStashedInMiB != -1) {
                MetricsLogger.histogram(context, "ota_stashed_in_MiBs", bytesStashedInMiB);
            }
            if (temperature_start != -1) {
                MetricsLogger.histogram(context, "ota_temperature_start", temperature_start);
            }
            if (temperature_end != -1) {
                MetricsLogger.histogram(context, "ota_temperature_end", temperature_end);
            }
            if (temperature_max != -1) {
                MetricsLogger.histogram(context, "ota_temperature_max", temperature_max);
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to read lines in last_install", e);
        }
    }

    /**
     * Called after booting to process and remove recovery-related files.
     * @return the log file from recovery, or null if none was found.
     *
     * @hide
     */
    public static String handleAftermath(Context context) {
        // Record the tail of the LOG_FILE
        String log = null;
        try {
            log = FileUtils.readTextFile(LOG_FILE, -LOG_FILE_MAX_LENGTH, "...\n");
        } catch (FileNotFoundException e) {
            Log.i(TAG, "No recovery log file");
        } catch (IOException e) {
            Log.e(TAG, "Error reading recovery log", e);
        }

        if (log != null) {
            parseLastInstallLog(context);
        }

        // Only remove the OTA package if it's partially processed (uncrypt'd).
        boolean reservePackage = BLOCK_MAP_FILE.exists();
        if (!reservePackage && UNCRYPT_PACKAGE_FILE.exists()) {
            String filename = null;
            try {
                filename = FileUtils.readTextFile(UNCRYPT_PACKAGE_FILE, 0, null);
            } catch (IOException e) {
                Log.e(TAG, "Error reading uncrypt file", e);
            }

            // Remove the OTA package on /data that has been (possibly
            // partially) processed. (Bug: 24973532)
            if (filename != null && filename.startsWith("/data")) {
                if (UNCRYPT_PACKAGE_FILE.delete()) {
                    Log.i(TAG, "Deleted: " + filename);
                } else {
                    Log.e(TAG, "Can't delete: " + filename);
                }
            }
        }

        // We keep the update logs (beginning with LAST_PREFIX), and optionally
        // the block map file (BLOCK_MAP_FILE) for a package. BLOCK_MAP_FILE
        // will be created at the end of a successful uncrypt. If seeing this
        // file, we keep the block map file and the file that contains the
        // package name (UNCRYPT_PACKAGE_FILE). This is to reduce the work for
        // GmsCore to avoid re-downloading everything again.
        String[] names = RECOVERY_DIR.list();
        for (int i = 0; names != null && i < names.length; i++) {
            if (names[i].startsWith(LAST_PREFIX)) continue;
            if (reservePackage && names[i].equals(BLOCK_MAP_FILE.getName())) continue;
            if (reservePackage && names[i].equals(UNCRYPT_PACKAGE_FILE.getName())) continue;

            recursiveDelete(new File(RECOVERY_DIR, names[i]));
        }

        return log;
    }

    /**
     * Internally, delete a given file or directory recursively.
     */
    private static void recursiveDelete(File name) {
        if (name.isDirectory()) {
            String[] files = name.list();
            for (int i = 0; files != null && i < files.length; i++) {
                File f = new File(name, files[i]);
                recursiveDelete(f);
            }
        }

        if (!name.delete()) {
            Log.e(TAG, "Can't delete: " + name);
        } else {
            Log.i(TAG, "Deleted: " + name);
        }
    }

    /**
     * Talks to RecoverySystemService via Binder to trigger uncrypt.
     */
    private boolean uncrypt(String packageFile, IRecoverySystemProgressListener listener) {
        try {
            return mService.uncrypt(packageFile, listener);
        } catch (RemoteException unused) {
        }
        return false;
    }

    /**
     * Talks to RecoverySystemService via Binder to set up the BCB.
     */
    private boolean setupBcb(String command) {
        try {
            return mService.setupBcb(command);
        } catch (RemoteException unused) {
        }
        return false;
    }

    /**
     * Talks to RecoverySystemService via Binder to clear up the BCB.
     */
    private boolean clearBcb() {
        try {
            return mService.clearBcb();
        } catch (RemoteException unused) {
        }
        return false;
    }

    /**
     * Talks to RecoverySystemService via Binder to set up the BCB command and
     * reboot into recovery accordingly.
     */
    private void rebootRecoveryWithCommand(String command) {
        try {
            mService.rebootRecoveryWithCommand(command);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Internally, recovery treats each line of the command file as a separate
     * argv, so we only need to protect against newlines and nulls.
     */
    private static String sanitizeArg(String arg) {
        arg = arg.replace('\0', '?');
        arg = arg.replace('\n', '?');
        return arg;
    }


    /**
     * @removed Was previously made visible by accident.
     */
    public RecoverySystem() {
        mService = null;
    }

    /**
     * @hide
     */
    public RecoverySystem(IRecoverySystem service) {
        mService = service;
    }
}
