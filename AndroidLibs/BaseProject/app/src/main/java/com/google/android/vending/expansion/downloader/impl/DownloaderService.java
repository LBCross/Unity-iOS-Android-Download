/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.google.android.vending.expansion.downloader.impl;

import com.google.android.vending.expansion.downloader.Constants;
import com.google.android.vending.expansion.downloader.DownloadProgressInfo;
import com.google.android.vending.expansion.downloader.DownloaderServiceMarshaller;
import com.google.android.vending.expansion.downloader.Helpers;
import com.google.android.vending.expansion.downloader.IDownloaderClient;
import com.google.android.vending.expansion.downloader.IDownloaderService;
import com.google.android.vending.expansion.downloader.IStub;
import com.google.android.vending.licensing.AESObfuscator;
import com.google.android.vending.licensing.APKExpansionPolicy;
import com.google.android.vending.licensing.LicenseChecker;
import com.google.android.vending.licensing.LicenseCheckerCallback;
import com.google.android.vending.licensing.Policy;
import com.joyyou.rosdk.SDKManager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Messenger;
import android.os.SystemClock;
import android.provider.Settings.Secure;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;

/**
 * Performs the background downloads requested by applications that use the
 * Downloads provider. This service does not run as a foreground task, so
 * Android may kill it off at will, but it will try to restart itself if it can.
 * Note that Android by default will kill off any process that has an open file
 * handle on the shared (SD Card) partition if the partition is unmounted.
 */
public abstract class DownloaderService extends CustomIntentService implements IDownloaderService {

    public DownloaderService() {
        super("LVLDownloadService");
    }

    private static final String LOG_TAG = "LVLDL";

    // the following NETWORK_* constants are used to indicates specific reasons
    // for disallowing a
    // download from using a network, since specific causes can require special
    // handling

    /**
     * The network is usable for the given download.
     */
    public static final int NETWORK_OK = 1;

    /**
     * There is no network connectivity.
     */
    public static final int NETWORK_NO_CONNECTION = 2;

    /**
     * The download exceeds the maximum size for this network.
     */
    public static final int NETWORK_UNUSABLE_DUE_TO_SIZE = 3;

    /**
     * The download exceeds the recommended maximum size for this network, the
     * user must confirm for this download to proceed without WiFi.
     */
    public static final int NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE = 4;

    /**
     * The current connection is roaming, and the download can't proceed over a
     * roaming connection.
     */
    public static final int NETWORK_CANNOT_USE_ROAMING = 5;

    /**
     * The app requesting the download specific that it can't use the current
     * network connection.
     */
    public static final int NETWORK_TYPE_DISALLOWED_BY_REQUESTOR = 6;

    /**
     * For intents used to notify the user that a download exceeds a size
     * threshold, if this extra is true, WiFi is required for this download
     * size; otherwise, it is only recommended.
     */
    public static final String EXTRA_IS_WIFI_REQUIRED = "isWifiRequired";
    public static final String EXTRA_FILE_NAME = "downloadId";

    /**
     * Used with DOWNLOAD_STATUS
     */
    public static final String EXTRA_STATUS_STATE = "ESS";
    public static final String EXTRA_STATUS_TOTAL_SIZE = "ETS";
    public static final String EXTRA_STATUS_CURRENT_FILE_SIZE = "CFS";
    public static final String EXTRA_STATUS_TOTAL_PROGRESS = "TFP";
    public static final String EXTRA_STATUS_CURRENT_PROGRESS = "CFP";

    public static final String ACTION_DOWNLOADS_CHANGED = "downloadsChanged";

    /**
     * Broadcast intent action sent by the download manager when a download
     * completes.
     */
    public final static String ACTION_DOWNLOAD_COMPLETE = "lvldownloader.intent.action.DOWNLOAD_COMPLETE";

    /**
     * Broadcast intent action sent by the download manager when download status
     * changes.
     */
    public final static String ACTION_DOWNLOAD_STATUS = "lvldownloader.intent.action.DOWNLOAD_STATUS";

    /*
     * Lists the states that the download manager can set on a download to
     * notify applications of the download progress. The codes follow the HTTP
     * families:<br> 1xx: informational<br> 2xx: success<br> 3xx: redirects (not
     * used by the download manager)<br> 4xx: client errors<br> 5xx: server
     * errors
     */

    /**
     * Returns whether the status is informational (i.e. 1xx).
     */
    public static boolean isStatusInformational(int status) {
        return (status >= 100 && status < 200);
    }

    /**
     * Returns whether the status is a success (i.e. 2xx).
     */
    public static boolean isStatusSuccess(int status) {
        return (status >= 200 && status < 300);
    }

    /**
     * Returns whether the status is an error (i.e. 4xx or 5xx).
     */
    public static boolean isStatusError(int status) {
        return (status >= 400 && status < 600 && status!=STATUS_INSUFFICIENT_SPACE_ERROR);
    }
    /**
     * Returns whether the status is an error (i.e. 4xx or 5xx).
     */
    public static boolean isStatusHasToDelete(int status) {
        return (status >= 400 && status < 600
                && status!=STATUS_INSUFFICIENT_SPACE_ERROR
                &&status!=STATUS_RETRY_TIMES_OUT
                &&status!=STATUS_CANNOT_RESUME);
    }
    /**
     * Returns whether the status is a client error (i.e. 4xx).
     */
    public static boolean isStatusClientError(int status) {
        return (status >= 400 && status < 500);
    }

    /**
     * Returns whether the status is a server error (i.e. 5xx).
     */
    public static boolean isStatusServerError(int status) {
        return (status >= 500 && status < 600);
    }

    /**
     * Returns whether the download has completed (either with success or
     * error).
     */
    public static boolean isStatusCompleted(int status) {
        return (status >= 200 && status < 300)
                || (status >= 400 && status < 600);
    }

    /**
     * This download hasn't stated yet
     */
    public static final int STATUS_PENDING = 190;

    /**
     * This download has started
     */
    public static final int STATUS_RUNNING = 192;

    /**
     * This download has been paused by the owning app.
     */
    public static final int STATUS_PAUSED_BY_APP = 193;

    /**
     * This download encountered some network error and is waiting before
     * retrying the request.
     */
    public static final int STATUS_WAITING_TO_RETRY = 194;

    /**
     * This download is waiting for network connectivity to proceed.
     */
    public static final int STATUS_WAITING_FOR_NETWORK = 195;

    /**
     * This download is waiting for a Wi-Fi connection to proceed or for
     * permission to download over cellular.
     */
    public static final int STATUS_QUEUED_FOR_WIFI_OR_CELLULAR_PERMISSION = 196;

    /**
     * This download is waiting for a Wi-Fi connection to proceed.
     */
    public static final int STATUS_QUEUED_FOR_WIFI = 197;

    /**
     * This download has successfully completed. Warning: there might be other
     * status values that indicate success in the future. Use isSucccess() to
     * capture the entire category.
     * 
     * @hide
     */
    public static final int STATUS_SUCCESS = 200;

    /**
     * The requested URL is no longer available
     */
    public static final int STATUS_FORBIDDEN = 403;
    /**
     * Retry Times Out
     */
    public static final int STATUS_RETRY_TIMES_OUT = 485;
    //file size mismatch
    public static final int STATUS_DOWNLOAD_FILE_SIZE_MISMATCH=486;
    /**
     * The file was delivered incorrectly
     */
    public static final int STATUS_FILE_DELIVERED_INCORRECTLY = 487;

    /**
     * The requested destination file already exists.
     */
    public static final int STATUS_FILE_ALREADY_EXISTS_ERROR = 488;

    /**
     * Some possibly transient error occurred, but we can't resume the download.
     */
    public static final int STATUS_CANNOT_RESUME = 489;

    /**
     * This download was canceled
     * 
     * @hide
     */
    public static final int STATUS_CANCELED = 490;

    /**
     * This download has completed with an error. Warning: there will be other
     * status values that indicate errors in the future. Use isStatusError() to
     * capture the entire category.
     */
    public static final int STATUS_UNKNOWN_ERROR = 491;

    /**
     * This download couldn't be completed because of a storage issue.
     * Typically, that's because the filesystem is missing or full. Use the more
     * specific {@link #STATUS_INSUFFICIENT_SPACE_ERROR} and
     * {@link #STATUS_DEVICE_NOT_FOUND_ERROR} when appropriate.
     * 
     * @hide
     */
    public static final int STATUS_FILE_ERROR = 492;

    /**
     * This download couldn't be completed because of an HTTP redirect response
     * that the download manager couldn't handle.
     * 
     * @hide
     */
    public static final int STATUS_UNHANDLED_REDIRECT = 493;

    /**
     * This download couldn't be completed because of an unspecified unhandled
     * HTTP code.
     * 
     * @hide
     */
    public static final int STATUS_UNHANDLED_HTTP_CODE = 494;

    /**
     * This download couldn't be completed because of an error receiving or
     * processing data at the HTTP level.
     * 
     * @hide
     */
    public static final int STATUS_HTTP_DATA_ERROR = 495;

    /**
     * This download couldn't be completed because of an HttpException while
     * setting up the request.
     * 
     * @hide
     */
    public static final int STATUS_HTTP_EXCEPTION = 496;

    /**
     * This download couldn't be completed because there were too many
     * redirects.
     * 
     * @hide
     */
    public static final int STATUS_TOO_MANY_REDIRECTS = 497;

    /**
     * This download couldn't be completed due to insufficient storage space.
     * Typically, this is because the SD card is full.
     * 
     * @hide
     */
    public static final int STATUS_INSUFFICIENT_SPACE_ERROR = 498;

    /**
     * This download couldn't be completed because no external storage device
     * was found. Typically, this is because the SD card is not mounted.
     * 
     * @hide
     */
    public static final int STATUS_DEVICE_NOT_FOUND_ERROR = 499;

    /**
     * This download is allowed to run.
     * 
     * @hide
     */
    public static final int CONTROL_RUN = 0;

    /**
     * This download must pause at the first opportunity.
     * 
     * @hide
     */
    public static final int CONTROL_PAUSED = 1;

    /**
     * This download is visible but only shows in the notifications while it's
     * in progress.
     * 
     * @hide
     */
    public static final int VISIBILITY_VISIBLE = 0;

    /**
     * This download is visible and shows in the notifications while in progress
     * and after completion.
     * 
     * @hide
     */
    public static final int VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1;

    /**
     * This download doesn't show in the UI or in the notifications.
     * 
     * @hide
     */
    public static final int VISIBILITY_HIDDEN = 2;

    /**
     * Bit flag for {@link #setAllowedNetworkTypes} corresponding to
     * {@link ConnectivityManager#TYPE_MOBILE}.
     */
    public static final int NETWORK_MOBILE = 1 << 0;

    /**
     * Bit flag for {@link #setAllowedNetworkTypes} corresponding to
     * {@link ConnectivityManager#TYPE_WIFI}.
     */
    public static final int NETWORK_WIFI = 1 << 1;

    private final static String TEMP_EXT = ".tmp";

    /**
     * Service thread status
     */
    private static boolean sIsRunning;

    @Override
    public IBinder onBind(Intent paramIntent) {
        Log.d(Constants.TAG, "Service Bound");
        return this.mServiceMessenger.getBinder();
    }

    /**
     * Network state.
     */
    private boolean mIsConnected;
    private boolean mIsFailover;
    private boolean mIsCellularConnection;
    private boolean mIsRoaming;
    private boolean mIsAtLeast3G;
    private boolean mIsAtLeast4G;
    private boolean mStateChanged;

    /**
     * Download state
     */
    private int mControl;
    private int mStatus;

    public boolean isWiFi() {
        return mIsConnected && !mIsCellularConnection;
    }

    /**
     * Bindings to important services
     */
    private ConnectivityManager mConnectivityManager;
    private WifiManager mWifiManager;

    /**
     * Package we are downloading for (defaults to package of application)
     */
    private PackageInfo mPackageInfo;

    /**
     * Byte counts
     */
    long mBytesSoFar;
    long mTotalLength;
    int mFileCount;

    /**
     * Used for calculating time remaining and speed
     */
    long mBytesAtSample;
    long mMillisecondsAtSample;
    float mAverageDownloadSpeed;

    /**
     * Our binding to the network state broadcasts
     */
    private BroadcastReceiver mConnReceiver;
    final private IStub mServiceStub = DownloaderServiceMarshaller.CreateStub(this);
    final private Messenger mServiceMessenger = mServiceStub.getMessenger();
    private Messenger mClientMessenger;
    private DownloadNotification mNotification;
    private PendingIntent mPendingIntent;
    private PendingIntent mAlarmIntent;

    /**
     * Updates the network type based upon the type and subtype returned from
     * the connectivity manager. Subtype is only used for cellular signals.
     * 
     * @param type
     * @param subType
     */
    private void updateNetworkType(int type, int subType) {
        switch (type) {
            case ConnectivityManager.TYPE_WIFI:
            case ConnectivityManager.TYPE_ETHERNET:
            case ConnectivityManager.TYPE_BLUETOOTH:
                mIsCellularConnection = false;
                mIsAtLeast3G = false;
                mIsAtLeast4G = false;
                break;
            case ConnectivityManager.TYPE_WIMAX:
                mIsCellularConnection = true;
                mIsAtLeast3G = true;
                mIsAtLeast4G = true;
                break;
            case ConnectivityManager.TYPE_MOBILE:
                mIsCellularConnection = true;
                switch (subType) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        mIsAtLeast3G = false;
                        mIsAtLeast4G = false;
                        break;
                    case TelephonyManager.NETWORK_TYPE_HSDPA:
                    case TelephonyManager.NETWORK_TYPE_HSUPA:
                    case TelephonyManager.NETWORK_TYPE_HSPA:
                    case TelephonyManager.NETWORK_TYPE_EVDO_0:
                    case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    case TelephonyManager.NETWORK_TYPE_UMTS:
                        mIsAtLeast3G = true;
                        mIsAtLeast4G = false;
                        break;
                    case TelephonyManager.NETWORK_TYPE_LTE: // 4G
                    case TelephonyManager.NETWORK_TYPE_EHRPD: // 3G ++ interop
                                                              // with 4G
                    case TelephonyManager.NETWORK_TYPE_HSPAP: // 3G ++ but
                                                              // marketed as
                                                              // 4G
                        mIsAtLeast3G = true;
                        mIsAtLeast4G = true;
                        break;
                    default:
                        mIsCellularConnection = false;
                        mIsAtLeast3G = false;
                        mIsAtLeast4G = false;
                }
        }
    }

    private void updateNetworkState(NetworkInfo info) {
        boolean isConnected = mIsConnected;
        boolean isFailover = mIsFailover;
        boolean isCellularConnection = mIsCellularConnection;
        boolean isRoaming = mIsRoaming;
        boolean isAtLeast3G = mIsAtLeast3G;
        if (null != info) {
            mIsRoaming = info.isRoaming();
            mIsFailover = info.isFailover();
            mIsConnected = info.isConnected();
            updateNetworkType(info.getType(), info.getSubtype());
        } else {
            mIsRoaming = false;
            mIsFailover = false;
            mIsConnected = false;
            updateNetworkType(-1, -1);
        }
        mStateChanged = (mStateChanged || isConnected != mIsConnected
                || isFailover != mIsFailover
                || isCellularConnection != mIsCellularConnection
                || isRoaming != mIsRoaming || isAtLeast3G != mIsAtLeast3G);
        if (Constants.LOGVV) {
            if (mStateChanged) {
                Log.v(LOG_TAG, "Network state changed: ");
                Log.v(LOG_TAG, "Starting State: " +
                        (isConnected ? "Connected " : "Not Connected ") +
                        (isCellularConnection ? "Cellular " : "WiFi ") +
                        (isRoaming ? "Roaming " : "Local ") +
                        (isAtLeast3G ? "3G+ " : "<3G "));
                Log.v(LOG_TAG, "Ending State: " +
                        (mIsConnected ? "Connected " : "Not Connected ") +
                        (mIsCellularConnection ? "Cellular " : "WiFi ") +
                        (mIsRoaming ? "Roaming " : "Local ") +
                        (mIsAtLeast3G ? "3G+ " : "<3G "));

                if (isServiceRunning()) {
                    if (mIsRoaming) {
                        mStatus = STATUS_WAITING_FOR_NETWORK;
                        mControl = CONTROL_PAUSED;
                    } else if (mIsCellularConnection) {
                        DownloadsDB db = DownloadsDB.getDB(this);
                        int flags = db.getFlags();
                        if (0 == (flags & FLAGS_DOWNLOAD_OVER_CELLULAR)) {
                            mStatus = STATUS_QUEUED_FOR_WIFI;
                            mControl = CONTROL_PAUSED;
                        }
                    }
                }

            }
        }
    }

    /**
     * Polls the network state, setting the flags appropriately.
     */
    void pollNetworkState() {
        if (null == mConnectivityManager) {
            mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        if (null == mWifiManager) {
            mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        }
        if (mConnectivityManager == null) {
            Log.w(Constants.TAG,
                    "couldn't get connectivity manager to poll network state");
        } else {
            NetworkInfo activeInfo = mConnectivityManager
                    .getActiveNetworkInfo();
            updateNetworkState(activeInfo);
        }
    }

    public static final int NO_DOWNLOAD_REQUIRED = 0;
    public static final int LVL_CHECK_REQUIRED = 1;
    public static final int DOWNLOAD_REQUIRED = 2;

    public static final String EXTRA_PACKAGE_NAME = "EPN";
    public static final String EXTRA_PENDING_INTENT = "EPI";
    public static final String EXTRA_MESSAGE_HANDLER = "EMH";


    /**
     * Careful! Only use this internally.
     * 
     * @return whether we think the service is running
     */
    private static synchronized boolean isServiceRunning() {
        return sIsRunning;
    }

    private static synchronized void setServiceRunning(boolean isRunning) {
        sIsRunning = isRunning;
    }

    public static int startDownloadServiceIfRequired(Context context,
            Intent intent, Class<?> serviceClass) throws NameNotFoundException {
        final PendingIntent pendingIntent = (PendingIntent) intent
                .getParcelableExtra(EXTRA_PENDING_INTENT);
        return startDownloadServiceIfRequired(context, pendingIntent,
                serviceClass);
    }

    public static int startDownloadServiceIfRequired(Context context,
            PendingIntent pendingIntent, Class<?> serviceClass)
            throws NameNotFoundException
    {
        String packageName = context.getPackageName();
        String className = serviceClass.getName();

        return startDownloadServiceIfRequired(context, pendingIntent,
                packageName, className);
    }

    /**
     * Starts the download if necessary. This function starts a flow that does `
     * many things. 1) Checks to see if the APK version has been checked and the
     * metadata database updated 2) If the APK version does not match, checks
     * the new LVL status to see if a new download is required 3) If the APK
     * version does match, then checks to see if the download(s) have been
     * completed 4) If the downloads have been completed, returns
     * NO_DOWNLOAD_REQUIRED The idea is that this can be called during the
     * startup of an application to quickly ascertain if the application needs
     * to wait to hear about any updated APK expansion files. Note that this
     * does mean that the application MUST be run for the first time with a
     * network connection, even if Market delivers all of the files.
     * 
     * @param context
     * @param thisIntent
     * @return true if the app should wait for more guidance from the
     *         downloader, false if the app can continue
     * @throws NameNotFoundException
     */
    public static int startDownloadServiceIfRequired(Context context,
            PendingIntent pendingIntent, String classPackage, String className)
            throws NameNotFoundException {

        int status = DOWNLOAD_REQUIRED;
        Intent fileIntent = new Intent();
        fileIntent.setClassName(classPackage, className);
        fileIntent.putExtra(EXTRA_PENDING_INTENT, pendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(fileIntent);
        }
        else{
            context.startService(fileIntent);
        }

        return status;
    }

    @Override
    public void requestAbortDownload() {
        mControl = CONTROL_PAUSED;
        mStatus = STATUS_CANCELED;
    }

    @Override
    public void requestPauseDownload() {
        mControl = CONTROL_PAUSED;
        mStatus = STATUS_PAUSED_BY_APP;
    }

    @Override
    public void setDownloadFlags(int flags) {
        Log.e("DownloadFlags",String.valueOf(flags));
        DownloadsDB.getDB(this).updateFlags(flags);
    }

    @Override
    public void requestContinueDownload() {
        if (mControl == CONTROL_PAUSED) {
            mControl = CONTROL_RUN;
        }
        Intent fileIntent = new Intent(this, this.getClass());
        fileIntent.putExtra(EXTRA_PENDING_INTENT, mPendingIntent);
        this.startService(fileIntent);
    }

    public abstract String getPublicKey();

    public abstract byte[] getSALT();

    public abstract String getAlarmReceiverClassName();
    private void UpdateNotificationStatus(Context mContext,int status){
        PackageInfo pi;
        DownloadsDB db;
        try {
            db = DownloadsDB.getDB(mContext);
            pi = mContext.getPackageManager().getPackageInfo(
                    mContext.getPackageName(), 0);
            Class<?> serviceClass = DownloaderService.this.getClass();
            switch (startDownloadServiceIfRequired(mContext, mPendingIntent,
                    serviceClass)) {
                case NO_DOWNLOAD_REQUIRED:
                    mNotification
                            .onDownloadStateChanged(IDownloaderClient.STATE_COMPLETED);
                    break;
                case LVL_CHECK_REQUIRED:
                    // DANGER WILL ROBINSON!
                    Log.e(LOG_TAG, "In LVL checking loop!");
                    mNotification
                            .onDownloadStateChanged(IDownloaderClient.STATE_FAILED_UNLICENSED);
                    throw new RuntimeException(
                            "Error with LVL checking and database integrity");
                case DOWNLOAD_REQUIRED:
                    // do nothing. the download will notify the
                    // application
                    // when things are done
                    break;
            }
        } catch (NameNotFoundException e1) {
            e1.printStackTrace();
            throw new RuntimeException(
                    "Error with getting information from package name");
        }
    }
    public int DownloadFromCDN(Context mContext){
        DownloadsDB db = DownloadsDB.getDB(mContext);

        int status = 0;
        for (int i = 0; i < db.waitingDownloads.size(); i++) {
            status = DownloadURI(mContext, db.waitingDownloads.get(i).mUri,db.waitingDownloads.get(i).mFileName,db.waitingDownloads.get(i).mFileMd5,db.waitingDownloads.get(i).mTotalBytes,i);
        }
        db.waitingDownloads.clear();
        UpdateNotificationStatus(mContext,status);
        return status;
    }
    private int DownloadURI(Context mContext,String url,String currentFileName,String md5,long fileSize,int i){
        DownloadsDB db = DownloadsDB.getDB(mContext);

        int status = 0;
        if (null != currentFileName) {
            DownloadInfo di = new DownloadInfo(i, currentFileName,url,fileSize,md5);

            if (handleFileUpdated(db, i, currentFileName,
                    fileSize)) {
                status |= -1;
                di.resetDownload();
                di.mUri = url;
                di.mFileMd5 = md5;
                di.mTotalBytes = fileSize;
                di.mStatus = status;
                db.updateDownload(di);
            } else {
                DownloadInfo dbdi = db
                        .getDownloadInfoByFileName(di.mFileName);
                if (null == dbdi) {
                    Log.d(LOG_TAG, "file " + di.mFileName
                            + " found. Not downloading.");
                    di.mStatus = STATUS_SUCCESS;
                    di.mTotalBytes = fileSize;
                    di.mCurrentBytes = fileSize;
                    di.mUri = url;
                    db.updateDownload(di);
                } else if (dbdi.mStatus != STATUS_SUCCESS) {
                    // we just update the URL
                    dbdi.mUri = url;
                    dbdi.mFileMd5= md5;
                    db.updateDownload(dbdi);
                    status |= -1;
                }
            }
        }
        return status;
    }

    private class LVLRunnable implements Runnable {
        LVLRunnable(Context context, PendingIntent intent) {
            mContext = context;
            mPendingIntent = intent;
        }

        final Context mContext;

        @Override
        public void run() {
            setServiceRunning(true);
            mNotification.onDownloadStateChanged(IDownloaderClient.STATE_FETCHING_URL);
            DownloadFromCDN(mContext);

        }

    };

    /**
     * Updates the LVL information from the server.
     * 
     * @param context
     */
    public void updateLVL(final Context context) {
        Context c = context.getApplicationContext();
        Handler h = new Handler(c.getMainLooper());
        h.post(new LVLRunnable(c, mPendingIntent));
    }

    /**
     * The APK has been updated and a filename has been sent down from the
     * Market call. If the file has the same name as the previous file, we do
     * nothing as the file is guaranteed to be the same. If the file does not
     * have the same name, we download it if it hasn't already been delivered by
     * Market.
     * 
     * @param index the index of the file from market (0 = main, 1 = patch)
     * @param filename the name of the new file
     * @param fileSize the size of the new file
     * @return
     */
    public boolean handleFileUpdated(DownloadsDB db, int index,
            String filename, long fileSize) {
        DownloadInfo di = db.getDownloadInfoByFileName(filename);
        if (null != di) {
            String oldFile = di.mFileName;
            // cleanup
            if (null != oldFile) {
                if (filename.equals(oldFile)) {
                    return false;
                }

                // remove partially downloaded file if it is there
                String deleteFile = Helpers.generateSaveFileName(this, oldFile);
                File f = new File(deleteFile);
                if (f.exists())
                    f.delete();
            }
        }
        return !Helpers.doesFileExist(this, filename, fileSize, true);
    }

    private void scheduleAlarm(long wakeUp) {
        AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            Log.e(Constants.TAG, "couldn't get alarm manager");
            return;
        }

        if (Constants.LOGV) {
            Log.v(Constants.TAG, "scheduling retry in " + wakeUp + "ms");
        }

        String className = getAlarmReceiverClassName();
        Intent intent = new Intent(Constants.ACTION_RETRY);
        intent.putExtra(EXTRA_PENDING_INTENT, mPendingIntent);
        intent.setClassName(this.getPackageName(),
                className);
        mAlarmIntent = PendingIntent.getBroadcast(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT);
        alarms.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + wakeUp, mAlarmIntent
                );
    }

    private void cancelAlarms() {
        if (null != mAlarmIntent) {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms == null) {
                Log.e(Constants.TAG, "couldn't get alarm manager");
                return;
            }
            alarms.cancel(mAlarmIntent);
            mAlarmIntent = null;
        }
    }

    /**
     * We use this to track network state, such as when WiFi, Cellular, etc. is
     * enabled when downloads are paused or in progress.
     */
    private class InnerBroadcastReceiver extends BroadcastReceiver {
        final Service mService;

        InnerBroadcastReceiver(Service service) {
            mService = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            pollNetworkState();
            if (mStateChanged
                    && !isServiceRunning()) {
                Log.d(Constants.TAG, "InnerBroadcastReceiver Called");
                Intent fileIntent = new Intent(context, mService.getClass());
                fileIntent.putExtra(EXTRA_PENDING_INTENT, mPendingIntent);
                // send a new intent to the service
                context.startService(fileIntent);
            }
        }
    };

    /**
     * This is the main thread for the Downloader. This thread is responsible
     * for queuing up downloads and other goodness.
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        setServiceRunning(true);
        try {
            // the database automatically reads the metadata for version code
            // and download status when the instance is created
            DownloadsDB db = DownloadsDB.getDB(this);
            final PendingIntent pendingIntent = (PendingIntent) intent
                    .getParcelableExtra(EXTRA_PENDING_INTENT);

            if (null != pendingIntent)
            {
                mNotification.setClientIntent(pendingIntent);
                mPendingIntent = pendingIntent;
            } else if (null != mPendingIntent) {
                mNotification.setClientIntent(mPendingIntent);
            } else {
                Log.e(LOG_TAG, "Downloader started in bad state without notification intent.");
                return;
            }

            // when the LVL check completes, a successful response will update
            // the service
            if (db.waitingDownloads.size()>0){
                updateLVL(this);
                return;
            }

            // get each download
            DownloadInfo[] infos = db.getDownloads();
            mBytesSoFar = 0;
            mTotalLength = 0;
            mFileCount = infos.length;
            for (DownloadInfo info : infos) {
                // We do an (simple) integrity check on each file, just to make
                // sure
                if (info.mStatus == STATUS_SUCCESS) {
                    // verify that the file matches the state
                    if (!Helpers.doesFileExist(this, info.mFileName, info.mTotalBytes, true)) {
                        info.mStatus = 0;
                        info.mCurrentBytes = 0;
                    }
                }
                // get aggregate data
                mTotalLength += info.mTotalBytes;
                mBytesSoFar += info.mCurrentBytes;
            }

            // loop through all downloads and fetch them
            pollNetworkState();
            if (null == mConnReceiver) {

                /**
                 * We use this to track network state, such as when WiFi,
                 * Cellular, etc. is enabled when downloads are paused or in
                 * progress.
                 */
                mConnReceiver = new InnerBroadcastReceiver(this);
                IntentFilter intentFilter = new IntentFilter(
                        ConnectivityManager.CONNECTIVITY_ACTION);
                intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                registerReceiver(mConnReceiver, intentFilter);
            }

            for (DownloadInfo info : infos) {
                long startingCount = info.mCurrentBytes;

                if (info.mStatus != STATUS_SUCCESS) {
                    DownloadThread dt = new DownloadThread(info, this, mNotification);
                    cancelAlarms();
                    scheduleAlarm(Constants.ACTIVE_THREAD_WATCHDOG);
                    dt.run();
                    cancelAlarms();
                }
                db.updateFromDb(info);
                boolean setWakeWatchdog = false;
                int notifyStatus;
                switch (info.mStatus) {
                    case STATUS_FORBIDDEN:
                        // the URL is out of date
                        updateLVL(this);
                        return;
                    case STATUS_SUCCESS:
                        mBytesSoFar += info.mCurrentBytes - startingCount;
                        continue;
                    case STATUS_FILE_DELIVERED_INCORRECTLY:
                        // we may be on a network that is returning us a web
                        // page on redirect
                        notifyStatus = IDownloaderClient.STATE_PAUSED_NETWORK_SETUP_FAILURE;
                        info.mCurrentBytes = 0;
                        db.updateDownload(info);
                        setWakeWatchdog = true;
                        break;
                    case STATUS_DOWNLOAD_FILE_SIZE_MISMATCH:
                        notifyStatus = IDownloaderClient.STATE_FAILED_FILE_SIZE_MISMATCH;
                        info.mCurrentBytes = 0;
                        db.updateDownload(info);
                        setWakeWatchdog = false;
                        break;
                    case STATUS_PAUSED_BY_APP:
                        notifyStatus = IDownloaderClient.STATE_PAUSED_BY_REQUEST;
                        break;
                    case STATUS_WAITING_FOR_NETWORK:
                    case STATUS_WAITING_TO_RETRY:
                        notifyStatus = IDownloaderClient.STATE_PAUSED_NETWORK_UNAVAILABLE;
                        setWakeWatchdog = true;
                        break;
                    case STATUS_QUEUED_FOR_WIFI_OR_CELLULAR_PERMISSION:
                    case STATUS_QUEUED_FOR_WIFI:
                        // look for more detail here
                        if (null != mWifiManager) {
                            if (!mWifiManager.isWifiEnabled()) {
                                notifyStatus = IDownloaderClient.STATE_PAUSED_WIFI_DISABLED_NEED_CELLULAR_PERMISSION;
                                setWakeWatchdog = true;
                                break;
                            }
                        }
                        notifyStatus = IDownloaderClient.STATE_PAUSED_NEED_CELLULAR_PERMISSION;
                        setWakeWatchdog = true;
                        break;
                    case STATUS_CANCELED:
                        notifyStatus = IDownloaderClient.STATE_FAILED_CANCELED;
                        setWakeWatchdog = true;
                        break;

                    case STATUS_INSUFFICIENT_SPACE_ERROR:
                        notifyStatus = IDownloaderClient.STATE_FAILED_SDCARD_FULL;
                        setWakeWatchdog = true;
                        break;

                    case STATUS_DEVICE_NOT_FOUND_ERROR:
                        notifyStatus = IDownloaderClient.STATE_PAUSED_SDCARD_UNAVAILABLE;
                        setWakeWatchdog = true;
                        break;

                    default:
                        notifyStatus = IDownloaderClient.STATE_FAILED;
                        break;
                }
                if (setWakeWatchdog) {
                    scheduleAlarm(Constants.WATCHDOG_WAKE_TIMER);
                } else {
                    cancelAlarms();
                }
                // failure or pause state
                mNotification.onDownloadStateChanged(notifyStatus);
                return;
            }

            // all downloads complete
            mNotification.onDownloadStateChanged(IDownloaderClient.STATE_COMPLETED);
        } finally {
            setServiceRunning(false);
        }
    }

    @Override
    public void onDestroy() {
        if (null != mConnReceiver) {
            unregisterReceiver(mConnReceiver);
            mConnReceiver = null;
        }
        mServiceStub.disconnect(this);
        super.onDestroy();
    }

    public int getNetworkAvailabilityState(DownloadsDB db) {
        if (mIsConnected) {
            if (!mIsCellularConnection)
                return NETWORK_OK;
            int flags = db.mFlags;
            if (mIsRoaming)
                return NETWORK_CANNOT_USE_ROAMING;
            if (0 != (flags & FLAGS_DOWNLOAD_OVER_CELLULAR)) {
                return NETWORK_OK;
            } else {
                return NETWORK_TYPE_DISALLOWED_BY_REQUESTOR;
            }
        }
        return NETWORK_NO_CONNECTION;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            mPackageInfo = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            ApplicationInfo ai = getApplicationInfo();
            CharSequence applicationLabel = getPackageManager().getApplicationLabel(ai);
            mNotification = new DownloadNotification(this, applicationLabel);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(DownloadNotification.NOTIFICATION_ID,mNotification.mDownloadNotification.updateNotification(this));
            }

        } catch (NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Exception thrown from methods called by generateSaveFile() for any fatal
     * error.
     */
    public static class GenerateSaveFileError extends Exception {
        private static final long serialVersionUID = 3465966015408936540L;
        int mStatus;
        String mMessage;

        public GenerateSaveFileError(int status, String message) {
            mStatus = status;
            mMessage = message;
        }
    }

    /**
     * Returns the filename (where the file should be saved) from info about a
     * download
     */
    public String generateTempSaveFileName(String fileName) {
        String path = Helpers.getSaveFilePath(this)
                + File.separator + fileName + TEMP_EXT;
        return path;
    }

    /**
     * Creates a filename (where the file should be saved) from info about a
     * download.
     */
    public String generateSaveFile(String filename, long filesize)
            throws GenerateSaveFileError {
        String path = generateTempSaveFileName(filename);
        File expPath = new File(path);
        if (!Helpers.isExternalMediaMounted()) {
            Log.d(Constants.TAG, "External media not mounted: " + path);
            throw new GenerateSaveFileError(STATUS_DEVICE_NOT_FOUND_ERROR,
                    "external media is not yet mounted");

        }
        if (expPath.exists()) {
            Log.d(Constants.TAG, "File already exists: " + path);
            throw new GenerateSaveFileError(STATUS_FILE_ALREADY_EXISTS_ERROR,
                    "requested destination file already exists");
        }
        File file = new File(path);
        long availableBytes = Helpers.getAvailableBytes(file.getParentFile());
        SDKManager.GetInstance().ULog("writeDataToDestination() path: "+ path +" availableBytes: "+ availableBytes,true);
        if (availableBytes < filesize) {
            throw new GenerateSaveFileError(STATUS_INSUFFICIENT_SPACE_ERROR,
                    "insufficient space on external storage");
        }
        return path;
    }

    /**
     * @return a non-localized string appropriate for logging corresponding to
     *         one of the NETWORK_* constants.
     */
    public String getLogMessageForNetworkError(int networkError) {
        switch (networkError) {
            case NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE:
                return "download size exceeds recommended limit for mobile network";

            case NETWORK_UNUSABLE_DUE_TO_SIZE:
                return "download size exceeds limit for mobile network";

            case NETWORK_NO_CONNECTION:
                return "no network connection available";

            case NETWORK_CANNOT_USE_ROAMING:
                return "download cannot use the current network connection because it is roaming";

            case NETWORK_TYPE_DISALLOWED_BY_REQUESTOR:
                return "download was requested to not use the current network type";

            default:
                return "unknown error with network connectivity";
        }
    }

    public int getControl() {
        return mControl;
    }

    public int getStatus() {
        return mStatus;
    }

    /**
     * Calculating a moving average for the speed so we don't get jumpy
     * calculations for time etc.
     */
    static private final float SMOOTHING_FACTOR = 0.005f;

    public void notifyUpdateBytes(long totalBytesSoFar) {
        long timeRemaining;
        long currentTime = SystemClock.uptimeMillis();
        float currentSpeedSample=0;
        if (0 != mMillisecondsAtSample) {
            // we have a sample.
            long timePassed = currentTime - mMillisecondsAtSample;
            long bytesInSample = totalBytesSoFar - mBytesAtSample;
            currentSpeedSample = (float) bytesInSample / (float) timePassed;
            if (0 != mAverageDownloadSpeed) {
                mAverageDownloadSpeed = SMOOTHING_FACTOR * currentSpeedSample
                        + (1 - SMOOTHING_FACTOR) * mAverageDownloadSpeed;
            } else {
                mAverageDownloadSpeed = currentSpeedSample;
            }
            timeRemaining = (long) ((mTotalLength - totalBytesSoFar) / mAverageDownloadSpeed);
        } else {
            timeRemaining = -1;
        }
        mMillisecondsAtSample = currentTime;
        mBytesAtSample = totalBytesSoFar;
        mNotification.onDownloadProgress(
                new DownloadProgressInfo(mTotalLength,
                        totalBytesSoFar,
                        timeRemaining,
                        currentSpeedSample)
                );

    }

    @Override
    protected boolean shouldStop() {
        // the database automatically reads the metadata for version code
        // and download status when the instance is created
        DownloadsDB db = DownloadsDB.getDB(this);
        if (db.mStatus == 0) {
            return true;
        }
        return false;
    }
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        mNotification.cancle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopSelf();
        }
    }
    @Override
    public void requestDownloadStatus() {
        mNotification.resendState();
    }

    @Override
    public void onClientUpdated(Messenger clientMessenger) {
        this.mClientMessenger = clientMessenger;
        mNotification.setMessenger(mClientMessenger);
    }

}
