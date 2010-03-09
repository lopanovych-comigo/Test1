/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2009-2010, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CommandsInterface.RadioState;

/**
 * {@hide}
 */
public abstract class IccCard {
    protected String mLogTag;
    protected boolean mDbg;

    private IccCardStatus mIccCardStatus = null;
    protected State mState = null;
    protected PhoneBase mPhone;
    private RegistrantList mAbsentRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mNetworkLockedRegistrants = new RegistrantList();

    private boolean mDesiredPinLocked;
    private boolean mDesiredFdnEnabled;
    private boolean mIccPinLocked = true; // Default to locked
    private boolean mIccFdnEnabled = false; // Default to disabled.
                                            // Will be updated when SIM_READY.
    private boolean mIccFdnAvailable = true; // Default is enabled.
                                             // Will be updated when SIM_READY.
    private boolean mIccPin2Blocked = false; // Default to disabled.
                                             // Will be updated when sim status changes.
    private boolean mIccPuk2Blocked = false; // Default to disabled.
                                             // Will be updated when sim status changes.

    private int mPin1RetryCount = -1;
    private int mPin2RetryCount = -1;
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_ICC_STATE = "ss";
    /* UNUSED means the ICC state not used (eg, nv ready) */
    static public final String INTENT_VALUE_ICC_UNUSED = "UNUSED";
    /* NOT_READY means the ICC interface is not ready (eg, radio is off or powering on) */
    static public final String INTENT_VALUE_ICC_NOT_READY = "NOT_READY";
    /* ABSENT means ICC is missing */
    static public final String INTENT_VALUE_ICC_ABSENT = "ABSENT";
    /* CARD_IO_ERROR means for three consecutive times there was SIM IO error */
    static public final String INTENT_VALUE_ICC_CARD_IO_ERROR = "CARD_IO_ERROR";
    /* LOCKED means ICC is locked by pin or by network */
    static public final String INTENT_VALUE_ICC_LOCKED = "LOCKED";
    /* READY means ICC is ready to access */
    static public final String INTENT_VALUE_ICC_READY = "READY";
    /* IMSI means ICC IMSI is ready in property */
    static public final String INTENT_VALUE_ICC_IMSI = "IMSI";
    /* LOADED means all ICC records, including IMSI, are loaded */
    static public final String INTENT_VALUE_ICC_LOADED = "LOADED";
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_LOCKED_REASON = "reason";
    /* PIN means ICC is locked on PIN1 */
    static public final String INTENT_VALUE_LOCKED_ON_PIN = "PIN";
    /* PUK means ICC is locked on PUK1 */
    static public final String INTENT_VALUE_LOCKED_ON_PUK = "PUK";
    /* NETWORK means ICC is locked on NETWORK PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK = "SIM NETWORK";
    /* NETWORK SUBSET means ICC is locked on NETWORK SUBSET PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_NETWORK_SUBSET = "SIM NETWORK SUBSET";
    /* CORPORATE means ICC is locked on CORPORATE PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_CORPORATE = "SIM CORPORATE";
    /* SERVICE PROVIDER means ICC is locked on SERVICE PROVIDER PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_SERVICE_PROVIDER = "SIM SERVICE PROVIDER";
    /* SIM means ICC is locked on SIM PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_SIM = "SIM SIM";
    /* RUIM NETWORK1 means ICC is locked on RUIM NETWORK1 PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_NETWORK1 = "RUIM NETWORK1";
    /* RUIM NETWORK2 means ICC is locked on RUIM NETWORK2 PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_NETWORK2 = "RUIM NETWORK2";
    /* RUIM HRPD means ICC is locked on RUIM HRPD PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_HRPD = "RUIM HRPD";
    /* RUIM CORPORATE means ICC is locked on RUIM CORPORATE PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_CORPORATE = "RUIM CORPORATE";
    /* RUIM SERVICE PROVIDER means ICC is locked on RUIM SERVICE PROVIDER PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_SERVICE_PROVIDER = "RUIM SERVICE PROVIDER";
    /* RUIM RUIM means ICC is locked on RUIM RUIM PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_RUIM_RUIM = "RUIM RUIM";

    protected static final int EVENT_ICC_LOCKED_OR_ABSENT = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 3;
    private static final int EVENT_PIN1PUK1_DONE = 4;
    private static final int EVENT_REPOLL_STATUS_DONE = 5;
    protected static final int EVENT_ICC_READY = 6;
    private static final int EVENT_QUERY_FACILITY_LOCK_DONE = 7;
    private static final int EVENT_CHANGE_FACILITY_LOCK_DONE = 8;
    private static final int EVENT_CHANGE_ICC_PASSWORD_DONE = 9;
    private static final int EVENT_QUERY_FACILITY_FDN_DONE = 10;
    private static final int EVENT_CHANGE_FACILITY_FDN_DONE = 11;
    protected static final int EVENT_ICC_STATUS_CHANGED = 12;
    private static final int EVENT_PIN2PUK2_DONE = 13;

    /*
      UNKNOWN is a transient state, for example, after uesr inputs ICC pin under
      PIN_REQUIRED state, the query for ICC status returns UNKNOWN before it
      turns to READY
     */
    public enum State {
        UNKNOWN,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        NETWORK_LOCKED,
        READY,
        CARD_IO_ERROR,
        SIM_NETWORK_SUBSET_LOCKED,
        SIM_CORPORATE_LOCKED,
        SIM_SERVICE_PROVIDER_LOCKED,
        SIM_SIM_LOCKED,
        RUIM_NETWORK1_LOCKED,
        RUIM_NETWORK2_LOCKED,
        RUIM_HRPD_LOCKED,
        RUIM_CORPORATE_LOCKED,
        RUIM_SERVICE_PROVIDER_LOCKED,
        RUIM_RUIM_LOCKED,
        NOT_READY;

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }
    }

    public State getState() {
        if (mState == null) {
            switch(mPhone.mCM.getRadioState()) {
                /* This switch block must not return anything in
                 * State.isLocked() or State.ABSENT.
                 * If it does, handleSimStatus() may break
                 */
                case RADIO_OFF:
                case RADIO_UNAVAILABLE:
                case SIM_NOT_READY:
                case RUIM_NOT_READY:
                    return State.UNKNOWN;
                case SIM_LOCKED_OR_ABSENT:
                case RUIM_LOCKED_OR_ABSENT:
                    //this should be transient-only
                    return State.UNKNOWN;
                case SIM_READY:
                case RUIM_READY:
                case NV_READY:
                    return State.READY;
                case NV_NOT_READY:
                    return State.ABSENT;
            }
        } else {
            return mState;
        }

        Log.e(mLogTag, "IccCard.getState(): case should never be reached");
        return State.UNKNOWN;
    }

    public IccCard(PhoneBase phone, String logTag, Boolean dbg) {
        mPhone = phone;
        mLogTag = logTag;
        mDbg = dbg;
    }

    abstract public void dispose();

    protected void finalize() {
        if(mDbg) Log.d(mLogTag, "IccCard finalized");
    }

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mAbsentRegistrants.add(r);

        if (getState() == State.ABSENT) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForAbsent(Handler h) {
        mAbsentRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    public void registerForNetworkLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mNetworkLockedRegistrants.add(r);

        if (getState() == State.NETWORK_LOCKED) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkLocked(Handler h) {
        mNetworkLockedRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mPinLockedRegistrants.add(r);

        if (getState().isPinLocked()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForLocked(Handler h) {
        mPinLockedRegistrants.remove(h);
    }


    /**
     * Supply the ICC PIN to the ICC
     *
     * When the operation is complete, onComplete will be sent to its
     * Handler.
     *
     * onComplete.obj will be an AsyncResult
     *
     * ((AsyncResult)onComplete.obj).exception == null on success
     * ((AsyncResult)onComplete.obj).exception != null on fail
     *
     * If the supplied PIN is incorrect:
     * ((AsyncResult)onComplete.obj).exception != null
     * && ((AsyncResult)onComplete.obj).exception
     *       instanceof com.android.internal.telephony.gsm.CommandException)
     * && ((CommandException)(((AsyncResult)onComplete.obj).exception))
     *          .getCommandError() == CommandException.Error.PASSWORD_INCORRECT
     *
     *
     */

    public void supplyPin (String pin, Message onComplete) {
        mPhone.mCM.supplyIccPin(pin, mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPuk (String puk, String newPin, Message onComplete) {
        mPhone.mCM.supplyIccPuk(puk, newPin,
                mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPin2 (String pin2, Message onComplete) {
        mPhone.mCM.supplyIccPin2(pin2,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    public void supplyPuk2 (String puk2, String newPin2, Message onComplete) {
        mPhone.mCM.supplyIccPuk2(puk2, newPin2,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    public void supplyNetworkDepersonalization (String pin, Message onComplete) {
        if(mDbg) log("Network Despersonalization: " + pin);
        mPhone.mCM.supplyNetworkDepersonalization(pin,
                mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    /**
     * Check whether fdn (fixed dialing number) service is available.
     * @return true if ICC fdn service available
     *         false if ICC fdn service not available
     */
     public boolean getIccFdnAvailable() {
         return mIccFdnAvailable;
     }

    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled() {
        return mIccPinLocked;
     }

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
     public boolean getIccFdnEnabled() {
        return mIccFdnEnabled;
     }

     /**
     * @return No. of Attempts remaining to unlock PIN1/PUK1
     */
    public int getIccPin1RetryCount() {
	return mPin1RetryCount;
    }

    /**
     * @return No. of Attempts remaining to unlock PIN2/PUK2
     */
    public int getIccPin2RetryCount() {
	return mPin2RetryCount;
    }


     /**
      * Set the ICC pin lock enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC pin state, aka. Pin1
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccLockEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX;

         mDesiredPinLocked = enabled;

         mPhone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_SIM,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_LOCK_DONE, onComplete));
     }

     /**
      * Set the ICC fdn enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC fdn enable, aka Pin2
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccFdnEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX +
                 CommandsInterface.SERVICE_CLASS_SMS;

         mDesiredFdnEnabled = enabled;

         mPhone.mCM.setFacilityLock(CommandsInterface.CB_FACILITY_BA_FD,
                 enabled, password, serviceClassX,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_FDN_DONE, onComplete));
     }

     /**
      * Change the ICC password used in ICC pin lock
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void changeIccLockPassword(String oldPassword, String newPassword,
             Message onComplete) {
         if(mDbg) log("Change Pin1 old: " + oldPassword + " new: " + newPassword);
         mPhone.mCM.changeIccPin(oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_ICC_PASSWORD_DONE, onComplete));

     }

     /**
      * Change the ICC password used in ICC fdn enable
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void changeIccFdnPassword(String oldPassword, String newPassword,
             Message onComplete) {
         if(mDbg) log("Change Pin2 old: " + oldPassword + " new: " + newPassword);
         mPhone.mCM.changeIccPin2(oldPassword, newPassword,
                 mHandler.obtainMessage(EVENT_CHANGE_ICC_PASSWORD_DONE, onComplete));

     }


    /**
     * Returns service provider name stored in ICC card.
     * If there is no service provider name associated or the record is not
     * yet available, null will be returned <p>
     *
     * Please use this value when display Service Provider Name in idle mode <p>
     *
     * Usage of this provider name in the UI is a common carrier requirement.
     *
     * Also available via Android property "gsm.sim.operator.alpha"
     *
     * @return Service Provider Name stored in ICC card
     *         null if no service provider name associated or the record is not
     *         yet available
     *
     */
    public abstract String getServiceProviderName();

    protected void updateStateProperty() {
        mPhone.setSystemProperty(TelephonyProperties.PROPERTY_SIM_STATE, getState().toString());
    }

    private void getIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(mLogTag,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        handleIccCardStatus((IccCardStatus) ar.result);
    }

    private void handleIccCardStatus(IccCardStatus newCardStatus) {
        boolean transitionedIntoPinLocked;
        boolean transitionedIntoAbsent;
        boolean transitionedIntoCardIOError;
        boolean transitionedIntoNetworkLocked;
        boolean transitionedIntoSimNetworkSubsetLocked;
        boolean transitionedIntoSimCorporateLocked;
        boolean transitionedIntoSimServiceProviderLocked;
        boolean transitionedIntoSimSimLocked;
        boolean transitionedIntoRuimNetwork1Locked;
        boolean transitionedIntoRuimNetwork2Locked;
        boolean transitionedIntoRuimHrpdLocked;
        boolean transitionedIntoRuimCorporateLocked;
        boolean transitionedIntoRuimServiceProviderLocked;
        boolean transitionedIntoRuimRuimLocked;

        State oldState, newState;

        oldState = mState;
        mIccCardStatus = newCardStatus;
        newState = getIccCardState();
        mState = newState;

        updateStateProperty();

        transitionedIntoPinLocked = (
                 (oldState != State.PIN_REQUIRED && newState == State.PIN_REQUIRED)
              || (oldState != State.PUK_REQUIRED && newState == State.PUK_REQUIRED));
        transitionedIntoAbsent = (oldState != State.ABSENT && newState == State.ABSENT);
        transitionedIntoCardIOError = (oldState != State.CARD_IO_ERROR
                && newState == State.CARD_IO_ERROR);
        transitionedIntoNetworkLocked = (oldState != State.NETWORK_LOCKED
                && newState == State.NETWORK_LOCKED);
        transitionedIntoSimNetworkSubsetLocked = (oldState != State.SIM_NETWORK_SUBSET_LOCKED
                && newState == State.SIM_NETWORK_SUBSET_LOCKED);
        transitionedIntoSimCorporateLocked = (oldState != State.SIM_CORPORATE_LOCKED
                && newState == State.SIM_CORPORATE_LOCKED);
        transitionedIntoSimServiceProviderLocked = (oldState != State.SIM_SERVICE_PROVIDER_LOCKED
                && newState == State.SIM_SERVICE_PROVIDER_LOCKED);
        transitionedIntoSimSimLocked = (oldState != State.SIM_SIM_LOCKED
                && newState == State.SIM_SIM_LOCKED);
        transitionedIntoRuimNetwork1Locked = (oldState != State.RUIM_NETWORK1_LOCKED
                && newState == State.RUIM_NETWORK1_LOCKED);
        transitionedIntoRuimNetwork2Locked = (oldState != State.RUIM_NETWORK2_LOCKED
                && newState == State.RUIM_NETWORK2_LOCKED);
        transitionedIntoRuimHrpdLocked = (oldState != State.RUIM_HRPD_LOCKED
                && newState == State.RUIM_HRPD_LOCKED);
        transitionedIntoRuimCorporateLocked = (oldState != State.RUIM_CORPORATE_LOCKED
                && newState == State.RUIM_CORPORATE_LOCKED);
        transitionedIntoRuimServiceProviderLocked = (oldState != State.RUIM_SERVICE_PROVIDER_LOCKED
                && newState == State.RUIM_SERVICE_PROVIDER_LOCKED);
        transitionedIntoRuimRuimLocked = (oldState != State.RUIM_RUIM_LOCKED
                && newState == State.RUIM_RUIM_LOCKED);

        if (transitionedIntoPinLocked) {
            if(mDbg) log("Notify SIM pin or puk locked.");
            mPinLockedRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                    (newState == State.PIN_REQUIRED) ?
                       INTENT_VALUE_LOCKED_ON_PIN : INTENT_VALUE_LOCKED_ON_PUK);
        } else if (transitionedIntoAbsent) {
            if(mDbg) log("Notify SIM missing.");
            mAbsentRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT, null);
        } else if (transitionedIntoCardIOError) {
            if(mDbg) log("Notify SIM Card IO Error.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_CARD_IO_ERROR, null);
        } else if (transitionedIntoNetworkLocked) {
            if(mDbg) log("Notify SIM network locked.");
            mNetworkLockedRegistrants.notifyRegistrants();
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_NETWORK);
        } else if (transitionedIntoSimNetworkSubsetLocked) {
            log("Notify SIM network Subset locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_NETWORK_SUBSET);
        } else if (transitionedIntoSimCorporateLocked) {
            log("Notify SIM Corporate locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_CORPORATE);
        } else if (transitionedIntoSimServiceProviderLocked) {
            log("Notify SIM Service Provider locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_SERVICE_PROVIDER);
        } else if (transitionedIntoSimSimLocked) {
            log("Notify SIM SIM locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_SIM);
        } else if (transitionedIntoRuimNetwork1Locked) {
            log("Notify RUIM network1 locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_NETWORK1);
        } else if (transitionedIntoRuimNetwork2Locked) {
            log("Notify RUIM network2 locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_NETWORK2);
        } else if (transitionedIntoRuimHrpdLocked) {
            log("Notify RUIM hrpd locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_HRPD);
        } else if (transitionedIntoRuimCorporateLocked) {
            log("Notify RUIM Corporate locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_CORPORATE);
        } else if (transitionedIntoRuimServiceProviderLocked) {
            log("Notify RUIM Service Provider locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_SERVICE_PROVIDER);
        } else if (transitionedIntoRuimRuimLocked) {
            log("Notify RUIM RUIM locked.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                  INTENT_VALUE_LOCKED_RUIM_RUIM);
        }
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFdnEnabled(AsyncResult ar) {
        if(ar.exception != null) {
            if(mDbg) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            if (ints[0] != 2) {
                mIccFdnEnabled = (0!=ints[0]);
                mIccFdnAvailable = true;
            } else {
                if(mDbg) log("Query facility lock: FDN Service Unavailable!");
                mIccFdnAvailable = false;
                mIccFdnEnabled = false;
            }
            if(mDbg) log("Query facility lock for FDN : "  + mIccFdnEnabled);
        } else {
            Log.e(mLogTag, "[IccCard] Bogus facility lock response");
        }
    }

    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFacilityLock(AsyncResult ar) {
        if(ar.exception != null) {
            if (mDbg) log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if(ints.length != 0) {
            mIccPinLocked = (0!=ints[0]);
            if(mDbg) log("Query facility lock for SIM Lock : "  + mIccPinLocked);
        } else {
            Log.e(mLogTag, "[IccCard] Bogus facility lock response");
        }
    }

    /**
     * Parse the error response to obtain No of attempts remaining to unlock PIN1/PUK1
     */
    private void parsePinPukErrorResult(AsyncResult ar, boolean isPin1) {
	int[] intArray = (int[]) ar.result;
	int length = intArray.length;
	mPin1RetryCount = -1;
	mPin2RetryCount = -1;
	if (length > 0) {
	    if (isPin1) {
		mPin1RetryCount = intArray[0];
	    } else {
		mPin2RetryCount = intArray[0];
	    }
	}
    }

    public void broadcastIccStateChangedIntent(String value, String reason) {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, mPhone.getPhoneName());
        intent.putExtra(INTENT_KEY_LOCKED_REASON, reason);

        if (mPhone.mCM.getRadioState() == RadioState.NV_READY) {
            value = INTENT_VALUE_ICC_UNUSED;
        }
        intent.putExtra(INTENT_KEY_ICC_STATE, value);

        if(mDbg) log("Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                + " reason " + reason);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg){
            AsyncResult ar;
            int serviceClassX;

            serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                            CommandsInterface.SERVICE_CLASS_DATA +
                            CommandsInterface.SERVICE_CLASS_FAX;

	    if (!mPhone.mIsTheCurrentActivePhone) {
		Log.e(mLogTag, "Received message " + msg +
		    "[" + msg.what + "] while being destroyed. Ignoring.");
		return;
	    }

            switch (msg.what) {
                case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                    mState = null;
                    updateStateProperty();
                    broadcastIccStateChangedIntent(INTENT_VALUE_ICC_NOT_READY, null);
                    break;
                case EVENT_ICC_READY:
                    //TODO: put facility read in SIM_READY now, maybe in REG_NW
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_FD, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_FDN_DONE));
                    break;
                case EVENT_ICC_LOCKED_OR_ABSENT:
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    mPhone.mCM.queryFacilityLock (
                            CommandsInterface.CB_FACILITY_BA_SIM, "", serviceClassX,
                            obtainMessage(EVENT_QUERY_FACILITY_LOCK_DONE));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    ar = (AsyncResult)msg.obj;

                    getIccCardStatusDone(ar);
                    break;
                case EVENT_PIN1PUK1_DONE:
		case EVENT_PIN2PUK2_DONE:
                    // a PIN/PUK/PIN2/PUK2/Network Personalization
                    // request has completed. ar.userObj is the response Message
                    // Repoll before returning
                    ar = (AsyncResult)msg.obj;
                    // TODO should abstract these exceptions
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
		    if ((ar.exception != null) && (ar.result != null)) {
			if (msg.what == EVENT_PIN1PUK1_DONE) {
			    parsePinPukErrorResult(ar, true);
			} else {
			    parsePinPukErrorResult(ar, false);
			}
		    }
                    mPhone.mCM.getIccCardStatus(
                        obtainMessage(EVENT_REPOLL_STATUS_DONE, ar.userObj));
                    break;
                case EVENT_REPOLL_STATUS_DONE:
                    // Finished repolling status after PIN operation
                    // ar.userObj is the response messaeg
                    // ar.userObj.obj is already an AsyncResult with an
                    // appropriate exception filled in if applicable

                    ar = (AsyncResult)msg.obj;
                    getIccCardStatusDone(ar);
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_QUERY_FACILITY_LOCK_DONE:
                    ar = (AsyncResult)msg.obj;
                    onQueryFacilityLock(ar);
                    break;
                case EVENT_QUERY_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;
                    onQueryFdnEnabled(ar);
                    break;
                case EVENT_CHANGE_FACILITY_LOCK_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mIccPinLocked = mDesiredPinLocked;
                        if (mDbg) log( "EVENT_CHANGE_FACILITY_LOCK_DONE: " +
                                "mIccPinLocked= " + mIccPinLocked);
                    } else {
			if (ar.result != null) {
			    parsePinPukErrorResult(ar, true);
			}
                        Log.e(mLogTag, "Error change facility lock with exception "
                            + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception == null) {
                        mIccFdnEnabled = mDesiredFdnEnabled;
                        if (mDbg) log("EVENT_CHANGE_FACILITY_FDN_DONE: " +
                                "mIccFdnEnabled=" + mIccFdnEnabled);
                    } else {
			if (ar.result != null) {
			    parsePinPukErrorResult(ar, false);
			}
                        Log.e(mLogTag, "Error change facility fdn with exception "
                                + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_ICC_PASSWORD_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        Log.e(mLogTag, "Error in change sim password with exception"
                            + ar.exception);
			if (ar.result != null) {
			    parsePinPukErrorResult(ar, true);
			}
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_ICC_STATUS_CHANGED:
                    Log.d(mLogTag, "Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mPhone.mCM.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    break;
                default:
                    Log.e(mLogTag, "[IccCard] Unknown Event " + msg.what);
            }
        }
    };

    public State getIccCardState() {
        if (mIccCardStatus == null) {
            Log.e(mLogTag, "[IccCard] IccCardStatus is null");
            return IccCard.State.ABSENT;
        }

        // this is common for all radio technologies
        // Presently all SIM card statuses except card present are treated as
        // ABSENT. Handling Card IO error case seperately.
        if (!mIccCardStatus.getCardState().isCardPresent()) {
            if (mIccCardStatus.getCardState().isCardFaulty() &&
                SystemProperties.getBoolean("persist.cust.tel.adapt",false)) {
                return IccCard.State.CARD_IO_ERROR;
            }
            return IccCard.State.ABSENT;
        }

        RadioState currentRadioState = mPhone.mCM.getRadioState();
        // check radio technology
        if( currentRadioState == RadioState.RADIO_OFF         ||
            currentRadioState == RadioState.RADIO_UNAVAILABLE ||
            currentRadioState == RadioState.SIM_NOT_READY     ||
            currentRadioState == RadioState.RUIM_NOT_READY    ||
            currentRadioState == RadioState.NV_NOT_READY      ||
            currentRadioState == RadioState.NV_READY) {
            return IccCard.State.NOT_READY;
        }

        if( currentRadioState == RadioState.SIM_LOCKED_OR_ABSENT  ||
            currentRadioState == RadioState.SIM_READY             ||
            currentRadioState == RadioState.RUIM_LOCKED_OR_ABSENT ||
            currentRadioState == RadioState.RUIM_READY) {

            int index;

            // check for CDMA radio technology
            if (currentRadioState == RadioState.RUIM_LOCKED_OR_ABSENT ||
                currentRadioState == RadioState.RUIM_READY) {
                index = mIccCardStatus.getCdmaSubscriptionAppIndex();
            }
            else {
                index = mIccCardStatus.getGsmUmtsSubscriptionAppIndex();
            }
	    IccCardApplication app;
	    if ((index < mIccCardStatus.CARD_MAX_APPS) && (index >= 0)) {
		app = mIccCardStatus.getApplication(index);
	    } else {
		Log.e(mLogTag, "[IccCard] Invalid Subscription Application index:" + index);
		return IccCard.State.ABSENT;
	    }

            if (app == null) {
                Log.e(mLogTag, "[IccCard] Subscription Application in not present");
                return IccCard.State.ABSENT;
            }

            Log.i(mLogTag, "PIN1 Status " + app.pin1 + "PIN2 Status " + app.pin2);
            if (app.pin2.isPinBlocked()) {
                Log.i(mLogTag, "PIN2 is blocked, PUK2 required.");
                mIccPin2Blocked = true;
                mIccPuk2Blocked = false;
            } else if (app.pin2.isPukBlocked()) {
                Log.i(mLogTag, "PUK2 is permanently blocked.");
                mIccPuk2Blocked = true;
                mIccPin2Blocked = false;
            } else {
                Log.i(mLogTag, "Neither PIN2 nor PUK2 is blocked.");
                mIccPin2Blocked = false;
                mIccPuk2Blocked = false;
            }

            // check if PIN required
            if (app.app_state.isPinRequired()) {
                return IccCard.State.PIN_REQUIRED;
            }
            if (app.app_state.isPukRequired()) {
                return IccCard.State.PUK_REQUIRED;
            }
            if (app.app_state.isSubscriptionPersoEnabled()) {
                //Following De-Personalizations are supported
                //as specified in 3GPP TS 22.022, and 3GPP2 C.S0068-0.
                //01.PERSOSUBSTATE_SIM_NETWORK
                //02.PERSOSUBSTATE_SIM_NETWORK_SUBSET
                //03.PERSOSUBSTATE_SIM_CORPORATE
                //04.PERSOSUBSTATE_SIM_SERVICE_PROVIDER
                //05.PERSOSUBSTATE_SIM_SIM
                //06.PERSOSUBSTATE_RUIM_NETWORK1
                //07.PERSOSUBSTATE_RUIM_NETWORK2
                //08.PERSOSUBSTATE_RUIM_HRPD
                //09.PERSOSUBSTATE_RUIM_CORPORATE
                //10.PERSOSUBSTATE_RUIM_SERVICE_PROVIDER
                //11.PERSOSUBSTATE_RUIM_RUIM
                log("ICC is Perso Locked, substate " + app.perso_substate);
                if (app.perso_substate.isPersoSubStateSimNetwork()) {
                    return IccCard.State.NETWORK_LOCKED;
                } else if (app.perso_substate.isPersoSubStateSimNetworkSubset()) {
                    return IccCard.State.SIM_NETWORK_SUBSET_LOCKED;
                } else if (app.perso_substate.isPersoSubStateSimCorporate()) {
                    return IccCard.State.SIM_CORPORATE_LOCKED;
                } else if (app.perso_substate.isPersoSubStateSimServiceProvider()) {
                    return IccCard.State.SIM_SERVICE_PROVIDER_LOCKED;
                } else if (app.perso_substate.isPersoSubStateSimSim()) {
                    return IccCard.State.SIM_SIM_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimNetwork1()) {
                    return IccCard.State.RUIM_NETWORK1_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimNetwork2()) {
                    return IccCard.State.RUIM_NETWORK2_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimHrpd()) {
                    return IccCard.State.RUIM_HRPD_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimCorporate()) {
                    return IccCard.State.RUIM_CORPORATE_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimServiceProvider()) {
                    return IccCard.State.RUIM_SERVICE_PROVIDER_LOCKED;
                } else if (app.perso_substate.isPersoSubStateRuimRuim()) {
                    return IccCard.State.RUIM_RUIM_LOCKED;
                } else {
                    Log.e(mLogTag,"[IccCard] UnSupported De-Personalization, substate "
                          + app.perso_substate + " assuming ICC_NOT_READY");
                    return IccCard.State.NOT_READY;
                }
            }
            if (app.app_state.isAppReady()) {
                return IccCard.State.READY;
            }
            if (app.app_state.isAppNotReady()) {
                return IccCard.State.NOT_READY;
            }
            return IccCard.State.NOT_READY;
        }

        return IccCard.State.ABSENT;
    }


    public boolean isApplicationOnIcc(IccCardApplication.AppType type) {
        if (mIccCardStatus == null) return false;

        for (int i = 0 ; i < mIccCardStatus.getNumApplications(); i++) {
            IccCardApplication app = mIccCardStatus.getApplication(i);
            if (app != null && app.app_type == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        if (mIccCardStatus == null) {
            return false;
        } else {
            // Returns ICC card status for both GSM and CDMA mode
            return mIccCardStatus.getCardState().isCardPresent();
        }
    }

    /**
     * @return true if ICC card is PIN2 blocked
     */
    public boolean getIccPin2Blocked() {
        return mIccPin2Blocked;
    }

    /**
     * @return true if ICC card is PUK2 blocked
     */
    public boolean getIccPuk2Blocked() {
        return mIccPuk2Blocked;
    }

    private void log(String msg) {
        Log.d(mLogTag, "[IccCard] " + msg);
    }
}
