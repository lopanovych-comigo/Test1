/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.INetStatService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Checkin;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.TelephonyEventLog;
import com.android.internal.telephony.DataConnection.FailCause;
import com.android.internal.telephony.RILConstants;

import java.io.IOException;
import java.util.ArrayList;

/**
 * {@hide}
 */
public final class GsmDataConnectionTracker extends DataConnectionTracker {
    protected final String LOG_TAG = "GSM";

    private GSMPhone mGsmPhone;
    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(EVENT_APN_CHANGED));
        }
    }

    //***** Instance Variables

    INetStatService netstat;
    // Indicates baseband will not auto-attach
    private boolean noAutoAttach = false;

    private boolean mReregisterOnReconnectFailure = false;
    private ContentResolver mResolver;

    // Count of PDP reset attempts; reset when we see incoming,
    // call reRegisterNetwork, or pingTest succeeds.
    private int mPdpResetCount = 0;
    private boolean mIsScreenOn = true;

    /** Delay between APN attempts */
    protected static final int APN_DELAY_MILLIS = 5000;

    //useful for debugging
    boolean failNextConnect = false;

    /**
     * allApns holds all apns for this sim spn, retrieved from
     * the Carrier DB.
     *
     * Create once after simcard info is loaded
     */
    private ArrayList<ApnSetting> allApns = null;

    /**
     * waitingApns holds all apns that are waiting to be connected
     *
     * It is a subset of allApns and has the same format
     */
    private ArrayList<ApnSetting> waitingApns = null;

    private ApnSetting preferredApn = null;

    /* Currently active APN */
    protected ApnSetting mActiveApn;

    /**
     * pdpList holds all the PDP connection, i.e. IP Link in GPRS
     */
    private ArrayList<DataConnection> pdpList;

    /** Currently active PdpConnection */
    private PdpConnection mActivePdp;

    /** Is packet service restricted by network */
    private boolean mIsPsRestricted = false;

    //***** Constants

    // TODO: Increase this to match the max number of simultaneous
    // PDP contexts we plan to support.
    /**
     * Pool size of PdpConnection objects.
     */
    private static final int PDP_CONNECTION_POOL_SIZE = 1;

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    private static final String INTENT_RECONNECT_ALARM = "com.android.internal.telephony.gprs-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    static final Uri PREFERAPN_URI = Uri.parse("content://telephony/carriers/preferapn");
    static final String APN_ID = "apn_id";
    private boolean canSetPreferApn = false;

    // for tracking retrys on the default APN
    private RetryManager mDefaultRetryManager;
    // for tracking retrys on a secondary APN
    private RetryManager mSecondaryRetryManager;

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals((INTENT_RECONNECT_ALARM))) {
                Log.d(LOG_TAG, "GPRS reconnect alarm. Previous state was " + state);

                String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
                if (state == State.FAILED) {
                    Message msg = obtainMessage(EVENT_CLEAN_UP_CONNECTION);
                    msg.arg1 = 0; // tearDown is false
                    msg.obj = (String) reason;
                    sendMessage(msg);
                }
                sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when wifi got disabeled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and wont report disconnected til next enalbing.
                    mIsWifiConnected = false;
                }
            }
        }
    };

    /** Watches for changes to the APN db. */
    private ApnChangeObserver apnObserver;

    //***** Constructor

    GsmDataConnectionTracker(GSMPhone p) {
        super(p);
        mGsmPhone = p;
        p.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        p.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mSIMRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        p.mCM.registerForDataStateChanged (this, EVENT_DATA_STATE_CHANGED, null);
        p.mCT.registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        p.mCT.registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        p.mSST.registerForGprsAttached(this, EVENT_GPRS_ATTACHED, null);
        p.mSST.registerForGprsDetached(this, EVENT_GPRS_DETACHED, null);
        p.mSST.registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        p.mSST.registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        p.mSST.registerForPsRestrictedEnabled(this, EVENT_PS_RESTRICT_ENABLED, null);
        p.mSST.registerForPsRestrictedDisabled(this, EVENT_PS_RESTRICT_DISABLED, null);

        this.netstat = INetStatService.Stub.asInterface(ServiceManager.getService("netstat"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_RECONNECT_ALARM);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        // TODO: Why is this registering the phone as the receiver of the intent
        //       and not its own handler?
        p.getContext().registerReceiver(mIntentReceiver, filter, null, p);


        mDataConnectionTracker = this;
        mResolver = phone.getContext().getContentResolver();

        apnObserver = new ApnChangeObserver();
        p.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, apnObserver);

        createAllPdpList();

        if (SystemProperties.getBoolean("persist.cust.tel.sdc.feature",false)) {
            // The data call state is read during reboot.
            dataEnabled[APN_DEFAULT_ID] = getSocketDataCallEnabled();
            mMasterDataEnabled = dataEnabled[APN_DEFAULT_ID];
            Log.d(LOG_TAG, "data enabled =" + dataEnabled[APN_DEFAULT_ID]);
        } else {
            // This preference tells us 1) initial condition for "dataEnabled",
            // and 2) whether the RIL will setup the baseband to auto-PS
            // attach.
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(phone.getContext());
            dataEnabled[APN_DEFAULT_ID] = !sp.getBoolean(GSMPhone.DATA_DISABLED_ON_BOOT_KEY, false);
        }
        if (dataEnabled[APN_DEFAULT_ID]) {
            enabledCount++;
        }
        noAutoAttach = !dataEnabled[APN_DEFAULT_ID];

        if (!mRetryMgr.configure(SystemProperties.get("ro.gsm.data_retry_config"))) {
            if (!mRetryMgr.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                // Should never happen, log an error and default to a simple linear sequence.
                Log.e(LOG_TAG, "Could not configure using DEFAULT_DATA_RETRY_CONFIG="
                        + DEFAULT_DATA_RETRY_CONFIG);
                mRetryMgr.configure(20, 2000, 1000);
            }
        }

        mDefaultRetryManager = mRetryMgr;
        mSecondaryRetryManager = new RetryManager();

        if (!mSecondaryRetryManager.configure(SystemProperties.get(
                "ro.gsm.2nd_data_retry_config"))) {
            if (!mSecondaryRetryManager.configure(SECONDARY_DATA_RETRY_CONFIG)) {
                // Should never happen, log an error and default to a simple sequence.
                Log.e(LOG_TAG, "Could note configure using SECONDARY_DATA_RETRY_CONFIG="
                        + SECONDARY_DATA_RETRY_CONFIG);
                mSecondaryRetryManager.configure("max_retries=3, 333, 333, 333");
            }
        }
    }

    public void dispose() {
        //Unregister for all events
        phone.mCM.unregisterForAvailable(this);
        phone.mCM.unregisterForOffOrNotAvailable(this);
        mGsmPhone.mSIMRecords.unregisterForRecordsLoaded(this);
        phone.mCM.unregisterForDataStateChanged(this);
        mGsmPhone.mCT.unregisterForVoiceCallEnded(this);
        mGsmPhone.mCT.unregisterForVoiceCallStarted(this);
        mGsmPhone.mSST.unregisterForGprsAttached(this);
        mGsmPhone.mSST.unregisterForGprsDetached(this);
        mGsmPhone.mSST.unregisterForRoamingOn(this);
        mGsmPhone.mSST.unregisterForRoamingOff(this);
        mGsmPhone.mSST.unregisterForPsRestrictedEnabled(this);
        mGsmPhone.mSST.unregisterForPsRestrictedDisabled(this);

        phone.getContext().unregisterReceiver(this.mIntentReceiver);
        phone.getContext().getContentResolver().unregisterContentObserver(this.apnObserver);

        destroyAllPdpList();
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "GsmDataConnectionTracker finalized");
    }

    protected void setState(State s) {
        if (DBG) log ("setState: " + s);
        if (state != s) {
            if (s == State.INITING) { // request PDP context
                Checkin.updateStats(
                        phone.getContext().getContentResolver(),
                        Checkin.Stats.Tag.PHONE_GPRS_ATTEMPTED, 1, 0.0);
            }

            if (s == State.CONNECTED) { // pppd is up
                Checkin.updateStats(
                        phone.getContext().getContentResolver(),
                        Checkin.Stats.Tag.PHONE_GPRS_CONNECTED, 1, 0.0);
            }
        }

        state = s;

        if (state == State.FAILED) {
            if (waitingApns != null)
                waitingApns.clear(); // when teardown the connection and set to IDLE
        }
    }

    public String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    protected String getActiveApnString() {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    /**
     * The data connection is expected to be setup while device
     *  1. has sim card
     *  2. registered to gprs service
     *  3. user doesn't explicitly disable data service
     *  4. wifi is not on
     *
     * @return false while no data connection if all above requirements are met.
     */
    public boolean isDataConnectionAsDesired() {
        boolean roaming = phone.getServiceState().getRoaming();

        if (mGsmPhone.mSIMRecords.getRecordsLoaded() &&
                mGsmPhone.mSST.getCurrentGprsState() == ServiceState.STATE_IN_SERVICE &&
                (!roaming || getDataOnRoamingEnabled()) &&
            !mIsWifiConnected &&
            !mIsPsRestricted ) {
            return (state == State.CONNECTED);
        }
        return true;
    }

    @Override
    protected boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    @Override
    protected boolean isApnTypeAvailable(String type) {
        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Formerly this method was ArrayList<PdpConnection> getAllPdps()
     */
    public ArrayList<DataConnection> getAllDataConnections() {
        ArrayList<DataConnection> pdps = (ArrayList<DataConnection>)pdpList.clone();
        return pdps;
    }

    private boolean isDataAllowed() {
        boolean roaming = phone.getServiceState().getRoaming();
        return getAnyDataEnabled() && (!roaming || getDataOnRoamingEnabled()) &&
                mMasterDataEnabled;
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    protected void onGprsDetached() {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        stopNetStatPoll();
        phone.notifyDataConnection(Phone.REASON_GPRS_DETACHED);
    }

    private void onGprsAttached() {
        if (state == State.CONNECTED) {
            startNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_GPRS_ATTACHED);
        } else {
            if (state == State.FAILED) {
                cleanUpConnection(false, Phone.REASON_GPRS_ATTACHED);
                mRetryMgr.resetRetryCount();
            }
            trySetupData(Phone.REASON_GPRS_ATTACHED);
        }
    }

    private boolean trySetupData(String reason) {
        if (DBG) log("***trySetupData due to " + (reason == null ? "(unspecified)" : reason));

        Log.d(LOG_TAG, "[DSAC DEB] " + "trySetupData with mIsPsRestricted=" + mIsPsRestricted);

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(reason);

            Log.i(LOG_TAG, "(fix?) We're on the simulator; assuming data is connected");
            return true;
        }
        if (SystemProperties.getBoolean("persist.cust.tel.sdc.feature",false)) {
            if (!getSocketDataCallEnabled()) {
                Log.i(LOG_TAG, "Socket data call is disabled");
                return false;
            }
        }

        int gprsState = mGsmPhone.mSST.getCurrentGprsState();
        boolean desiredPowerState = mGsmPhone.mSST.getDesiredPowerState();

        if ((state == State.IDLE || state == State.SCANNING)
                && (gprsState == ServiceState.STATE_IN_SERVICE || noAutoAttach)
                && mGsmPhone.mSIMRecords.getRecordsLoaded()
                && isDataAllowed()
                && !mIsPsRestricted
                && desiredPowerState ) {

            if (state == State.IDLE) {
                waitingApns = buildWaitingApns();
                if (waitingApns.isEmpty()) {
                    if (DBG) log("No APN found");
                    notifyNoData(PdpConnection.FailCause.MISSING_UKNOWN_APN);
                    return false;
                } else {
                    log ("Create from allApns : " + apnListToString(allApns));
                }
            }

            if (DBG) {
                log ("Setup waitngApns : " + apnListToString(waitingApns));
            }
            return setupData(reason);
        } else {
            if (DBG)
                log("trySetupData: Not ready for data: " +
                    " dataState=" + state +
                    " gprsState=" + gprsState +
                    " sim=" + mGsmPhone.mSIMRecords.getRecordsLoaded() +
                    " UMTS=" + mGsmPhone.mSST.isConcurrentVoiceAndData() +
                    " phoneState=" + phone.getState() +
                    " isDataAllowed=" + isDataAllowed() +
                    " dataEnabled=" + getAnyDataEnabled() +
                    " roaming=" + phone.getServiceState().getRoaming() +
                    " dataOnRoamingEnable=" + getDataOnRoamingEnabled() +
                    " ps restricted=" + mIsPsRestricted +
                    " desiredPowerState=" + desiredPowerState +
                    " MasterDataEnabled=" + mMasterDataEnabled);
            return false;
        }
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     * @param tearDown true if the underlying PdpConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     */
    private void cleanUpConnection(boolean tearDown, String reason) {
        if (DBG) log("Clean up connection due to " + reason);

        // Clear the reconnect alarm, if set.
        if (mReconnectIntent != null) {
            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            am.cancel(mReconnectIntent);
            mReconnectIntent = null;
        }

        setState(State.DISCONNECTING);

        for (DataConnection conn : pdpList) {
            PdpConnection pdp = (PdpConnection) conn;
            if (tearDown) {
                Message msg = obtainMessage(EVENT_DISCONNECT_DONE, reason);
                pdp.disconnect(msg);
            } else {
                pdp.clearSettings();
            }
        }
        stopNetStatPoll();

        if (!tearDown) {
            setState(State.IDLE);
            phone.notifyDataConnection(reason);
            mActiveApn = null;
        }
    }

    /**
     * @param types comma delimited list of APN types
     * @return array of APN types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = Phone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    private ArrayList<ApnSetting> createApnList(Cursor cursor) {
        ArrayList<ApnSetting> result = new ArrayList<ApnSetting>();
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                ApnSetting apn = new ApnSetting(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types);
                result.add(apn);
            } while (cursor.moveToNext());
        }
        return result;
    }

    private PdpConnection findFreePdp() {
        for (DataConnection conn : pdpList) {
            PdpConnection pdp = (PdpConnection) conn;
            if (pdp.getState() == DataConnection.State.INACTIVE) {
                return pdp;
            }
        }
        return null;
    }

    private boolean setupData(String reason) {
        ApnSetting apn;
        PdpConnection pdp;

        apn = getNextApn();
        if (apn == null) return false;
        pdp = findFreePdp();
        if (pdp == null) {
            if (DBG) log("setupData: No free PdpConnection found!");
            return false;
        }
        mActiveApn = apn;
        mActivePdp = pdp;

        Message msg = obtainMessage();
        msg.what = EVENT_DATA_SETUP_COMPLETE;
        msg.obj = reason;
        // Setting the state to INITING before calling connect.
        // There can be a situation were response indicating connect
        // failure arrives before the connect request completes. This will
        // block all further attempts to establish data call.
        setState(State.INITING);
        pdp.connect(apn, msg);

        phone.notifyDataConnection(reason);
        return true;
    }

    protected String getInterfaceName(String apnType) {
        if (mActivePdp != null &&
                (apnType == null ||
                (mActiveApn != null && mActiveApn.canHandleType(apnType)))) {
            return mActivePdp.getInterface();
        }
        return null;
    }

    protected String getIpAddress(String apnType) {
        if (mActivePdp != null &&
                (apnType == null ||
                (mActiveApn != null && mActiveApn.canHandleType(apnType)))) {
            return mActivePdp.getIpAddress();
        }
        return null;
    }

    public String getGateway(String apnType) {
        if (mActivePdp != null &&
                (apnType == null ||
                (mActiveApn != null && mActiveApn.canHandleType(apnType)))) {
            return mActivePdp.getGatewayAddress();
        }
        return null;
    }

    protected String[] getDnsServers(String apnType) {
        if (mActivePdp != null &&
                (apnType == null ||
                (mActiveApn != null && mActiveApn.canHandleType(apnType)))) {
            return mActivePdp.getDnsServers();
        }
        return null;
    }

    private boolean
    pdpStatesHasCID (ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if (states.get(i).cid == cid) return true;
        }

        return false;
    }

    private boolean
    pdpStatesHasActiveCID (ArrayList<DataCallState> states, int cid) {
        for (int i = 0, s = states.size() ; i < s ; i++) {
            if ((states.get(i).cid == cid) && (states.get(i).active != 0)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        boolean isConnected;

        isConnected = (state != State.IDLE && state != State.FAILED);

        // The "current" may no longer be valid.  MMS depends on this to send properly.
        mGsmPhone.updateCurrentCarrierInProvider();

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        createAllApnList();
        if (IsPreferredApnChanged()) {
           if (state != State.DISCONNECTING) {
              cleanUpConnection(isConnected, Phone.REASON_APN_CHANGED);
              if (!isConnected) {
                 // reset reconnect timer
                 mRetryMgr.resetRetryCount();
                 mReregisterOnReconnectFailure = false;
                 trySetupData(Phone.REASON_APN_CHANGED);
             }
          }
       }
    }

    /**
     * Checks if the PreferredApn is Changed
     **/
    private boolean IsPreferredApnChanged() {
       boolean change = true;
       Log.d(LOG_TAG, "mActiveApn: "+ mActiveApn);
       Log.d(LOG_TAG, "Preferred APN: " +  preferredApn );

       if ((preferredApn != null) && (mActiveApn != null))  {
           if ((mActiveApn.toString().equals(preferredApn.toString() )) &&
               ((mActiveApn.user != null && mActiveApn.user.equals(preferredApn.user)) ||
                (mActiveApn.user == null && preferredApn.user == null)) &&
               ((mActiveApn.password != null && mActiveApn.password.equals(preferredApn.password)) ||
                (mActiveApn.password == null && preferredApn.password == null)))  {
                change = false;
          }
       }
       Log.d(LOG_TAG, "Is Preferred APN changed: "+ change);
       return change;
    }
    /**
     * @param explicitPoll if true, indicates that *we* polled for this
     * update while state == CONNECTED rather than having it delivered
     * via an unsolicited response (which could have happened at any
     * previous state
     */
    protected void onPdpStateChanged (AsyncResult ar, boolean explicitPoll) {
        ArrayList<DataCallState> pdpStates;

        pdpStates = (ArrayList<DataCallState>)(ar.result);

        if (ar.exception != null) {
            // This is probably "radio not available" or something
            // of that sort. If so, the whole connection is going
            // to come down soon anyway
            return;
        }

        if (state == State.CONNECTED) {
            // The way things are supposed to work, the PDP list
            // should not contain the CID after it disconnects.
            // However, the way things really work, sometimes the PDP
            // context is still listed with active = false, which
            // makes it hard to distinguish an activating context from
            // an activated-and-then deactivated one.
            if (!pdpStatesHasCID(pdpStates, cidActive)) {
                // It looks like the PDP context has deactivated.
                // Tear everything down and try to reconnect.

                Log.i(LOG_TAG, "PDP connection has dropped. Reconnecting");

                // Add an event log when the network drops PDP
                int cid = -1;
                GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
                if (loc != null) cid = loc.getCid();
                EventLog.List val = new EventLog.List(cid,
                        TelephonyManager.getDefault().getNetworkType());
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_PDP_NETWORK_DROP, val);

                // Check whether network mode is GSM , If network mode is
                // GSM tear down the data call and retry for new data call.
                // Else if network mode is CDMA tear down the data call
                // and do not retry setup data call. In order to avoid
                // retrying the data call pass reason as
                // RADIO_TECHNOLOGY_CHANGED
                if (mGsmPhone.getPhoneTypeFromNetworkType() == RILConstants.GSM_PHONE) {
                    cleanUpConnection(true, null);
                } else {
                    Log.i(LOG_TAG, "Data reconnect disabled due to radio technology changed");
                    cleanUpConnection(true, Phone.REASON_RADIO_TECHNOLOGY_CHANGED);
                }
                return;
            } else if (!pdpStatesHasActiveCID(pdpStates, cidActive)) {
                // Here, we only consider this authoritative if we asked for the
                // PDP list. If it was an unsolicited response, we poll again
                // to make sure everyone agrees on the initial state.

                if (!explicitPoll) {
                    // We think it disconnected but aren't sure...poll from our side
                    phone.mCM.getPDPContextList(
                            this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
                } else {
                    Log.i(LOG_TAG, "PDP connection has dropped (active=false case). "
                                    + " Reconnecting");

                    // Log the network drop on the event log.
                    int cid = -1;
                    GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
                    if (loc != null) cid = loc.getCid();
                    EventLog.List val = new EventLog.List(cid,
                            TelephonyManager.getDefault().getNetworkType());
                    EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_PDP_NETWORK_DROP, val);

                    // Check whether network mode is GSM , If network mode is
                    // GSM tear down the data call and retry for new data call.
                    // Else if network mode is CDMA tear down the data call
                    // and do not retry setup data call. In order to avoid
                    // retrying the data call pass reason as
                    // RADIO_TECHNOLOGY_CHANGED
                    if (mGsmPhone.getPhoneTypeFromNetworkType() == RILConstants.GSM_PHONE) {
                        cleanUpConnection(true, null);
                    } else {
                        Log.i(LOG_TAG, "Data reconnect disabled due to radio technology changed");
                        cleanUpConnection(true, Phone.REASON_RADIO_TECHNOLOGY_CHANGED);
                    }
                }
            }
        }
    }

    private void notifyDefaultData(String reason) {
        setupDnsProperties();
        setState(State.CONNECTED);
        phone.notifyDataConnection(reason);
        startNetStatPoll();
        // reset reconnect timer
        mRetryMgr.resetRetryCount();
        mReregisterOnReconnectFailure = false;
    }

    private void setupDnsProperties() {
        int mypid = android.os.Process.myPid();
        String[] servers = getDnsServers(null);
        String propName;
        String propVal;
        int count;

        count = 0;
        for (int i = 0; i < servers.length; i++) {
            String serverAddr = servers[i];
            if (!TextUtils.equals(serverAddr, "0.0.0.0")) {
                SystemProperties.set("net.dns" + (i+1) + "." + mypid, serverAddr);
                count++;
            }
        }
        for (int i = count+1; i <= 4; i++) {
            propName = "net.dns" + i + "." + mypid;
            propVal = SystemProperties.get(propName);
            if (propVal.length() != 0) {
                SystemProperties.set(propName, "");
            }
        }
        /*
         * Bump the property that tells the name resolver library
         * to reread the DNS server list from the properties.
         */
        propVal = SystemProperties.get("net.dnschange");
        if (propVal.length() != 0) {
            try {
                int n = Integer.parseInt(propVal);
                SystemProperties.set("net.dnschange", "" + (n+1));
            } catch (NumberFormatException e) {
            }
        }
    }

    /**
     * This is a kludge to deal with the fact that
     * the PDP state change notification doesn't always work
     * with certain RIL impl's/basebands
     *
     */
    private void startPeriodicPdpPoll() {
        removeMessages(EVENT_POLL_PDP);

        sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
    }

    private void resetPollStats() {
        txPkts = -1;
        rxPkts = -1;
        sentSinceLastRecv = 0;
        netStatPollPeriod = POLL_NETSTAT_MILLIS;
    }

    private void doRecovery() {
        if (state == State.CONNECTED) {
            int maxPdpReset = Settings.Gservices.getInt(mResolver,
                    Settings.Gservices.PDP_WATCHDOG_MAX_PDP_RESET_FAIL_COUNT,
                    DEFAULT_MAX_PDP_RESET_FAIL);
            if (mPdpResetCount < maxPdpReset) {
                mPdpResetCount++;
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_PDP_RESET, sentSinceLastRecv);
                cleanUpConnection(true, Phone.REASON_PDP_RESET);
            } else {
                mPdpResetCount = 0;
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_REREGISTER_NETWORK, sentSinceLastRecv);
                mGsmPhone.mSST.reRegisterNetwork(null);
            }
            // TODO: Add increasingly drastic recovery steps, eg,
            // reset the radio, reset the device.
        }
    }

    protected void startNetStatPoll() {
        if (state == State.CONNECTED && netStatPollEnabled == false) {
            Log.d(LOG_TAG, "[DataConnection] Start poll NetStat");
            resetPollStats();
            netStatPollEnabled = true;
            mPollNetStat.run();
        }
    }

    protected void stopNetStatPoll() {
        netStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        Log.d(LOG_TAG, "[DataConnection] Stop poll NetStat");
    }

    protected void restartRadio() {
        Log.d(LOG_TAG, "************TURN OFF RADIO**************");
        cleanUpConnection(true, Phone.REASON_RADIO_TURNED_OFF);
        phone.mCM.setRadioPower(false, null);
        /* Note: no need to call setRadioPower(true).  Assuming the desired
         * radio power state is still ON (as tracked by ServiceStateTracker),
         * ServiceStateTracker will call setRadioPower when it receives the
         * RADIO_STATE_CHANGED notification for the power off.  And if the
         * desired power state has changed in the interim, we don't want to
         * override it with an unconditional power on.
         */

        int reset = Integer.parseInt(SystemProperties.get("net.ppp.reset-by-timeout", "0"));
        SystemProperties.set("net.ppp.reset-by-timeout", String.valueOf(reset+1));
    }

    private Runnable mPollNetStat = new Runnable()
    {

        public void run() {
            long sent, received;
            long preTxPkts = -1, preRxPkts = -1;

            Activity newActivity;

            preTxPkts = txPkts;
            preRxPkts = rxPkts;

            try {
                txPkts = netstat.getMobileTxPackets();
                rxPkts = netstat.getMobileRxPackets();
            } catch (RemoteException e) {
                txPkts = 0;
                rxPkts = 0;
            }

            //Log.d(LOG_TAG, "rx " + String.valueOf(rxPkts) + " tx " + String.valueOf(txPkts));

            if (netStatPollEnabled && (preTxPkts > 0 || preRxPkts > 0)) {
                sent = txPkts - preTxPkts;
                received = rxPkts - preRxPkts;

                if ( sent > 0 && received > 0 ) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAINANDOUT;
                    mPdpResetCount = 0;
                } else if (sent > 0 && received == 0) {
                    if (phone.getState() == Phone.State.IDLE) {
                        sentSinceLastRecv += sent;
                    } else {
                        sentSinceLastRecv = 0;
                    }
                    newActivity = Activity.DATAOUT;
                } else if (sent == 0 && received > 0) {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.DATAIN;
                    mPdpResetCount = 0;
                } else if (sent == 0 && received == 0) {
                    newActivity = Activity.NONE;
                } else {
                    sentSinceLastRecv = 0;
                    newActivity = Activity.NONE;
                }

                if (activity != newActivity && mIsScreenOn) {
                    activity = newActivity;
                    phone.notifyDataActivity();
                }
            }

            if (mIsScreenOn) {
                netStatPollPeriod = Settings.Gservices.getInt(mResolver,
                        Settings.Gservices.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
            } else {
                netStatPollPeriod = Settings.Gservices.getInt(mResolver,
                        Settings.Gservices.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                        POLL_NETSTAT_SCREEN_OFF_MILLIS);
            }

            if (netStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, netStatPollPeriod);
            }
        }
    };

    /**
     * Returns true if the last fail cause is something that
     * seems like it deserves an error notification.
     * Transient errors are ignored
     */
    private boolean shouldPostNotification(PdpConnection.FailCause  cause) {
        boolean shouldPost = true;
        // TODO CHECK
        // if (dataLink != null) {
        //    shouldPost = dataLink.getLastLinkExitCode() != DataLink.EXIT_OPEN_FAILED;
        //}
        return (shouldPost && cause != PdpConnection.FailCause.UNKNOWN);
    }

    /**
     * Return true if data connection need to be setup after disconnected due to
     * reason.
     *
     * @param reason the reason why data is disconnected
     * @return true if try setup data connection is need for this reason
     */
    private boolean retryAfterDisconnected(String reason) {
        boolean retry = true;

        if ((Phone.REASON_RADIO_TURNED_OFF.equals(reason))
                || (Phone.REASON_RADIO_TECHNOLOGY_CHANGED.equals(reason))) {
            retry = false;
        }
        return retry;
    }

    private void reconnectAfterFail(FailCause lastFailCauseCode, String reason) {
        if (state == State.FAILED) {
            if (!mRetryMgr.isRetryNeeded()) {
                if (!mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
                    // if no more retries on a secondary APN attempt, tell the world and revert.
                    phone.notifyDataConnection(Phone.REASON_APN_FAILED);
                    onEnableApn(apnTypeToId(mRequestedApnType), DISABLED);
                    return;
                }
                if (mReregisterOnReconnectFailure) {
                    // We've re-registerd once now just retry forever.
                    mRetryMgr.retryForeverUsingLastTimeout();
                } else {
                    // Try to re-register to the network.
                    Log.d(LOG_TAG, "PDP activate failed, Reregistering to the network");
                    mReregisterOnReconnectFailure = true;
                    mGsmPhone.mSST.reRegisterNetwork(null);
                    mRetryMgr.resetRetryCount();
                    return;
                }
            }

            int nextReconnectDelay = mRetryMgr.getRetryTimer();
            Log.d(LOG_TAG, "PDP activate failed. Scheduling next attempt for "
                    + (nextReconnectDelay / 1000) + "s");

            AlarmManager am =
                (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(INTENT_RECONNECT_ALARM);
            intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, reason);
            mReconnectIntent = PendingIntent.getBroadcast(
                    phone.getContext(), 0, intent, 0);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + nextReconnectDelay,
                    mReconnectIntent);

            mRetryMgr.increaseRetryCount();

            if (!shouldPostNotification(lastFailCauseCode)) {
                Log.d(LOG_TAG,"NOT Posting GPRS Unavailable notification "
                                + "-- likely transient error");
            } else {
                notifyNoData(lastFailCauseCode);
            }
        }
    }

    private void notifyNoData(PdpConnection.FailCause lastFailCauseCode) {
        setState(State.FAILED);
    }

    protected void onRecordsLoaded() {
        createAllApnList();
        if (state == State.FAILED) {
            cleanUpConnection(false, null);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA, Phone.REASON_SIM_LOADED));
    }

    @Override
    protected void onEnableNewApn() {
        // change our retry manager to use the appropriate numbers for the new APN
        if (mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            mRetryMgr = mDefaultRetryManager;
        } else {
            mRetryMgr = mSecondaryRetryManager;
        }
        mRetryMgr.resetRetryCount();

        // TODO:  To support simultaneous PDP contexts, this should really only call
        // cleanUpConnection if it needs to free up a PdpConnection.
        cleanUpConnection(true, Phone.REASON_APN_SWITCHED);
    }

    protected boolean onTrySetupData(String reason) {
        return trySetupData(reason);
    }

    @Override
    protected void onRoamingOff() {
        trySetupData(Phone.REASON_ROAMING_OFF);
    }

    @Override
    protected void onRoamingOn() {
        if (getDataOnRoamingEnabled()) {
            trySetupData(Phone.REASON_ROAMING_ON);
        } else {
            if (DBG) log("Tear down data connection on roaming.");
            cleanUpConnection(true, Phone.REASON_ROAMING_ON);
        }
    }

    protected void onRadioAvailable() {
        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            setState(State.CONNECTED);
            phone.notifyDataConnection(null);

            Log.i(LOG_TAG, "We're on the simulator; assuming data is connected");
        }

        if (state != State.IDLE) {
            cleanUpConnection(true, null);
        }
    }

    protected void onRadioOffOrNotAvailable() {
        // Make sure our reconnect delay starts at the initial value
        // next time the radio comes on
        mRetryMgr.resetRetryCount();
        mReregisterOnReconnectFailure = false;

        if (phone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            Log.i(LOG_TAG, "We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("Radio is off and clean up all connection");
            // TODO: Should we reset mRequestedApnType to "default"?
            cleanUpConnection(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    protected void onDataSetupComplete(AsyncResult ar) {
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }

        if (ar.exception == null) {
            // everything is setup
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                SystemProperties.set("gsm.defaultpdpcontext.active", "true");
                        if (canSetPreferApn && preferredApn == null) {
                            Log.d(LOG_TAG, "PREFERED APN is null");
                            preferredApn = mActiveApn;
                            setPreferredApn(preferredApn.id);
                        }
            } else {
                SystemProperties.set("gsm.defaultpdpcontext.active", "false");
            }
            notifyDefaultData(reason);

            // TODO: For simultaneous PDP support, we need to build another
            // trigger another TRY_SETUP_DATA for the next APN type.  (Note
            // that the existing connection may service that type, in which
            // case we should try the next type, etc.
        } else {
            PdpConnection.FailCause cause;
            cause = (PdpConnection.FailCause) (ar.result);
            if(DBG) log("PDP setup failed " + cause);
                    // Log this failure to the Event Logs.
            if (cause.isEventLoggable()) {
                int cid = -1;
                GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
                if (loc != null) cid = loc.getCid();

                EventLog.List val = new EventLog.List(
                        cause.ordinal(), cid,
                        TelephonyManager.getDefault().getNetworkType());
                EventLog.writeEvent(TelephonyEventLog.EVENT_LOG_RADIO_PDP_SETUP_FAIL, val);
            }

            // No try for permanent failure
            if (cause.isPermanentFail()) {
                notifyNoData(cause);
                if (!mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
                    phone.notifyDataConnection(Phone.REASON_APN_FAILED);
                    onEnableApn(apnTypeToId(mRequestedApnType), DISABLED);
                }
                return;
            }

            waitingApns.remove(0);
            if (waitingApns.isEmpty()) {
                // No more to try, start delayed retry
                startDelayedRetry(cause, reason);
            } else {
                // we still have more apns to try
                setState(State.SCANNING);
                // Wait a bit before trying the next APN, so that
                // we're not tying up the RIL command channel
                sendMessageDelayed(obtainMessage(EVENT_TRY_SETUP_DATA, reason), APN_DELAY_MILLIS);
            }
        }
    }

    protected void onDisconnectDone(AsyncResult ar) {
        String reason = null;
        if(DBG) log("EVENT_DISCONNECT_DONE");
        if (ar.userObj instanceof String) {
           reason = (String) ar.userObj;
        }
        setState(State.IDLE);
        phone.notifyDataConnection(reason);
        mActiveApn = null;
        if (retryAfterDisconnected(reason)) {
            trySetupData(reason);
        }
    }

    protected void onPollPdp() {
        if (state == State.CONNECTED) {
            // only poll when connected
            phone.mCM.getPDPContextList(this.obtainMessage(EVENT_GET_PDP_LIST_COMPLETE));
            sendMessageDelayed(obtainMessage(EVENT_POLL_PDP), POLL_PDP_MILLIS);
        }
    }

    protected void onVoiceCallStarted() {
        if (state == State.CONNECTED && ! mGsmPhone.mSST.isConcurrentVoiceAndData()) {
            stopNetStatPoll();
            phone.notifyDataConnection(Phone.REASON_VOICE_CALL_STARTED);
        }
    }

    protected void onVoiceCallEnded() {
        if (state == State.CONNECTED) {
            if (!mGsmPhone.mSST.isConcurrentVoiceAndData()) {
                startNetStatPoll();
                phone.notifyDataConnection(Phone.REASON_VOICE_CALL_ENDED);
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        } else {
            // reset reconnect timer
            mRetryMgr.resetRetryCount();
            mReregisterOnReconnectFailure = false;
            // in case data setup was attempted when we were on a voice call
            trySetupData(Phone.REASON_VOICE_CALL_ENDED);
        }
    }

    protected void onCleanUpConnection(boolean tearDown, String reason) {
        cleanUpConnection(tearDown, reason);
    }

    /**
     * Based on the sim operator numeric, create a list for all possible pdps
     * with all apns associated with that pdp
     *
     *
     */
    private void createAllApnList() {
        allApns = new ArrayList<ApnSetting>();
        String operator = mGsmPhone.mSIMRecords.getSIMOperatorNumeric();

        if (operator != null) {
            String selection = "numeric = '" + operator + "'";

            Cursor cursor = phone.getContext().getContentResolver().query(
                    Telephony.Carriers.CONTENT_URI, null, selection, null, null);

            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    allApns = createApnList(cursor);
                    // TODO: Figure out where this fits in.  This basically just
                    // writes the pap-secrets file.  No longer tied to PdpConnection
                    // object.  Not used on current platform (no ppp).
                    //PdpConnection pdp = pdpList.get(pdp_name);
                    //if (pdp != null && pdp.dataLink != null) {
                    //    pdp.dataLink.setPasswordInfo(cursor);
                    //}
                }
                cursor.close();
            }
        }

        if (allApns.isEmpty()) {
            if (DBG) log("No APN found for carrier: " + operator);
            preferredApn = null;
            notifyNoData(PdpConnection.FailCause.MISSING_UKNOWN_APN);
        } else {
            preferredApn = getPreferredApn();
            Log.d(LOG_TAG, "Get PreferredAPN");
            if (preferredApn != null && !preferredApn.numeric.equals(operator)) {
                preferredApn = null;
                setPreferredApn(-1);
            }
        }
    }

    private void createAllPdpList() {
        pdpList = new ArrayList<DataConnection>();
        DataConnection pdp;

        for (int i = 0; i < PDP_CONNECTION_POOL_SIZE; i++) {
            pdp = new PdpConnection(mGsmPhone);
            pdpList.add(pdp);
         }
    }

    private void destroyAllPdpList() {
        if(pdpList != null) {
            PdpConnection pdp;
            pdpList.removeAll(pdpList);
        }
    }

    /**
     *
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns() {
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();
        String operator = mGsmPhone.mSIMRecords.getSIMOperatorNumeric();

        if (mRequestedApnType.equals(Phone.APN_TYPE_DEFAULT)) {
            if (canSetPreferApn && preferredApn != null) {
                Log.i(LOG_TAG, "Preferred APN:" + operator + ":"
                        + preferredApn.numeric + ":" + preferredApn);
                if (preferredApn.numeric.equals(operator)) {
                    Log.i(LOG_TAG, "Waiting APN set to preferred APN");
                    apnList.add(preferredApn);
                    return apnList;
                } else {
                    setPreferredApn(-1);
                    preferredApn = null;
                }
            }
        }

        if (allApns != null) {
            for (ApnSetting apn : allApns) {
                if (apn.canHandleType(mRequestedApnType)) {
                    apnList.add(apn);
                }
            }
        }
        return apnList;
    }

    /**
     * Get next apn in waitingApns
     * @return the first apn found in waitingApns, null if none
     */
    private ApnSetting getNextApn() {
        ArrayList<ApnSetting> list = waitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }

    private void startDelayedRetry(PdpConnection.FailCause cause, String reason) {
        notifyNoData(cause);
        reconnectAfterFail(cause, reason);
    }

    private void setPreferredApn(int pos) {
        if (!canSetPreferApn) {
            return;
        }

        ContentResolver resolver = phone.getContext().getContentResolver();
        resolver.delete(PREFERAPN_URI, null, null);

        if (pos >= 0) {
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(PREFERAPN_URI, values);
        }
    }

    private ApnSetting getPreferredApn() {
        if (allApns.isEmpty()) {
            return null;
        }

        Cursor cursor = phone.getContext().getContentResolver().query(
                PREFERAPN_URI, new String[] { "_id", "name", "apn" },
                null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            canSetPreferApn = true;
        } else {
            canSetPreferApn = false;
        }

        if (canSetPreferApn && cursor.getCount() > 0) {
            int pos;
            cursor.moveToFirst();
            pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for(ApnSetting p:allApns) {
                if (p.id == pos && p.canHandleType(mRequestedApnType)) {
                    cursor.close();
                    return p;
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        return null;
    }

    public void handleMessage (Message msg) {
        if (DBG) Log.d(LOG_TAG,"GSMDataConnTrack handleMessage "+msg);

        if (!mGsmPhone.mIsTheCurrentActivePhone) {
            Log.d(LOG_TAG, "Ignore GSM msgs since GSM phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_RECORDS_LOADED:
                onRecordsLoaded();
                break;

            case EVENT_GPRS_DETACHED:
                onGprsDetached();
                break;

            case EVENT_GPRS_ATTACHED:
                onGprsAttached();
                break;

            case EVENT_DATA_STATE_CHANGED:
                onPdpStateChanged((AsyncResult) msg.obj, false);
                break;

            case EVENT_GET_PDP_LIST_COMPLETE:
                onPdpStateChanged((AsyncResult) msg.obj, true);
                break;

            case EVENT_POLL_PDP:
                onPollPdp();
                break;

            case EVENT_START_NETSTAT_POLL:
                startNetStatPoll();
                break;

            case EVENT_START_RECOVERY:
                doRecovery();
                break;

            case EVENT_APN_CHANGED:
                onApnChanged();
                break;

            case EVENT_PS_RESTRICT_ENABLED:
                /**
                 * We don't need to explicitly to tear down the PDP context
                 * when PS restricted is enabled. The base band will deactive
                 * PDP context and notify us with PDP_CONTEXT_CHANGED.
                 * But we should stop the network polling and prevent reset PDP.
                 */
                Log.d(LOG_TAG, "[DSAC DEB] " + "EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                mIsPsRestricted = true;
                break;

            case EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                Log.d(LOG_TAG, "[DSAC DEB] " + "EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (state == State.CONNECTED) {
                    startNetStatPoll();
                } else {
                    if (state == State.FAILED) {
                        cleanUpConnection(false, Phone.REASON_PS_RESTRICT_ENABLED);
                        mRetryMgr.resetRetryCount();
                        mReregisterOnReconnectFailure = false;
                    }
                    trySetupData(Phone.REASON_PS_RESTRICT_ENABLED);
                }
                break;

            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmDataConnectionTracker] " + s);
    }

}
