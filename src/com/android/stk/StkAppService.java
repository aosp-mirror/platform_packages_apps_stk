/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.stk;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;

import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.LaunchBrowserMode;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.Input;
import com.android.internal.telephony.cat.ResultCode;
import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CatCmdMessage.BrowserSettings;
import com.android.internal.telephony.cat.CatCmdMessage.SetupEventListSettings;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.cat.CatService;

import java.util.LinkedList;
import java.lang.System;
import java.util.List;

import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.IDLE_SCREEN_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.LANGUAGE_SELECTION_EVENT;

/**
 * SIM toolkit application level service. Interacts with Telephopny messages,
 * application's launch and user input from STK UI elements.
 *
 */
public class StkAppService extends Service implements Runnable {

    // members
    protected class StkContext {
        protected CatCmdMessage mMainCmd = null;
        protected CatCmdMessage mCurrentCmd = null;
        protected CatCmdMessage mCurrentMenuCmd = null;
        protected Menu mCurrentMenu = null;
        protected String lastSelectedItem = null;
        protected boolean mMenuIsVisible = false;
        protected boolean mIsInputPending = false;
        protected boolean mIsMenuPending = false;
        protected boolean mIsDialogPending = false;
        protected boolean responseNeeded = true;
        protected boolean launchBrowser = false;
        protected BrowserSettings mBrowserSettings = null;
        protected LinkedList<DelayedCmd> mCmdsQ = null;
        protected boolean mCmdInProgress = false;
        protected int mStkServiceState = STATE_UNKNOWN;
        protected int mSetupMenuState = STATE_UNKNOWN;
        protected int mMenuState = StkMenuActivity.STATE_INIT;
        protected int mOpCode = -1;
        private Activity mActivityInstance = null;
        private Activity mDialogInstance = null;
        private Activity mMainActivityInstance = null;
        private int mSlotId = 0;
        private SetupEventListSettings mSetupEventListSettings = null;
        private boolean mClearSelectItem = false;
        private boolean mDisplayTextDlgIsVisibile = false;
        private CatCmdMessage mCurrentSetupEventCmd = null;
        private CatCmdMessage mIdleModeTextCmd = null;
        final synchronized void setPendingActivityInstance(Activity act) {
            CatLog.d(this, "setPendingActivityInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_ACT_INST, mSlotId, act);
        }
        final synchronized Activity getPendingActivityInstance() {
            CatLog.d(this, "getPendingActivityInstance act : " + mSlotId + ", " +
                    mActivityInstance);
            return mActivityInstance;
        }
        final synchronized void setPendingDialogInstance(Activity act) {
            CatLog.d(this, "setPendingDialogInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_DAL_INST, mSlotId, act);
        }
        final synchronized Activity getPendingDialogInstance() {
            CatLog.d(this, "getPendingDialogInstance act : " + mSlotId + ", " +
                    mDialogInstance);
            return mDialogInstance;
        }
        final synchronized void setMainActivityInstance(Activity act) {
            CatLog.d(this, "setMainActivityInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_MAINACT_INST, mSlotId, act);
        }
        final synchronized Activity getMainActivityInstance() {
            CatLog.d(this, "getMainActivityInstance act : " + mSlotId + ", " +
                    mMainActivityInstance);
            return mMainActivityInstance;
        }
    }

    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;
    private Context mContext = null;
    private NotificationManager mNotificationManager = null;
    static StkAppService sInstance = null;
    private AppInterface[] mStkService = null;
    private StkContext[] mStkContext = null;
    private int mSimCount = 0;
    private PowerManager mPowerManager = null;
    private StkCmdReceiver mStkCmdReceiver = null;

    // Used for setting FLAG_ACTIVITY_NO_USER_ACTION when
    // creating an intent.
    private enum InitiatedByUserAction {
        yes,            // The action was started via a user initiated action
        unknown,        // Not known for sure if user initated the action
    }

    // constants
    static final String OPCODE = "op";
    static final String CMD_MSG = "cmd message";
    static final String RES_ID = "response id";
    static final String MENU_SELECTION = "menu selection";
    static final String INPUT = "input";
    static final String HELP = "help";
    static final String CONFIRMATION = "confirm";
    static final String CHOICE = "choice";
    static final String SLOT_ID = "SLOT_ID";
    static final String STK_CMD = "STK CMD";
    static final String STK_DIALOG_URI = "stk://com.android.stk/dialog/";
    static final String STK_MENU_URI = "stk://com.android.stk/menu/";
    static final String STK_INPUT_URI = "stk://com.android.stk/input/";
    static final String STK_TONE_URI = "stk://com.android.stk/tone/";

    // These below constants are used for SETUP_EVENT_LIST
    static final String SETUP_EVENT_TYPE = "event";
    static final String SETUP_EVENT_CAUSE = "cause";

    // operations ids for different service functionality.
    static final int OP_CMD = 1;
    static final int OP_RESPONSE = 2;
    static final int OP_LAUNCH_APP = 3;
    static final int OP_END_SESSION = 4;
    static final int OP_BOOT_COMPLETED = 5;
    private static final int OP_DELAYED_MSG = 6;
    static final int OP_CARD_STATUS_CHANGED = 7;
    static final int OP_SET_ACT_INST = 8;
    static final int OP_SET_DAL_INST = 9;
    static final int OP_SET_MAINACT_INST = 10;
    static final int OP_LOCALE_CHANGED = 11;
    static final int OP_ALPHA_NOTIFY = 12;
    static final int OP_IDLE_SCREEN = 13;

    //Invalid SetupEvent
    static final int INVALID_SETUP_EVENT = 0xFF;

    // Response ids
    static final int RES_ID_MENU_SELECTION = 11;
    static final int RES_ID_INPUT = 12;
    static final int RES_ID_CONFIRM = 13;
    static final int RES_ID_DONE = 14;
    static final int RES_ID_CHOICE = 15;

    static final int RES_ID_TIMEOUT = 20;
    static final int RES_ID_BACKWARD = 21;
    static final int RES_ID_END_SESSION = 22;
    static final int RES_ID_EXIT = 23;

    static final int YES = 1;
    static final int NO = 0;

    static final int STATE_UNKNOWN = -1;
    static final int STATE_NOT_EXIST = 0;
    static final int STATE_EXIST = 1;

    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String STK_MENU_ACTIVITY_NAME = PACKAGE_NAME + ".StkMenuActivity";
    private static final String STK_INPUT_ACTIVITY_NAME = PACKAGE_NAME + ".StkInputActivity";
    private static final String STK_DIALOG_ACTIVITY_NAME = PACKAGE_NAME + ".StkDialogActivity";
    // Notification id used to display Idle Mode text in NotificationManager.
    private static final int STK_NOTIFICATION_ID = 333;
    private static final String LOG_TAG = new Object(){}.getClass().getEnclosingClass().getName();

    // Inner class used for queuing telephony messages (proactive commands,
    // session end) while the service is busy processing a previous message.
    private class DelayedCmd {
        // members
        int id;
        CatCmdMessage msg;
        int slotId;

        DelayedCmd(int id, CatCmdMessage msg, int slotId) {
            this.id = id;
            this.msg = msg;
            this.slotId = slotId;
        }
    }

    @Override
    public void onCreate() {
        CatLog.d(LOG_TAG, "onCreate()+");
        // Initialize members
        int i = 0;
        mContext = getBaseContext();
        mSimCount = TelephonyManager.from(mContext).getSimCount();
        CatLog.d(LOG_TAG, "simCount: " + mSimCount);
        mStkService = new AppInterface[mSimCount];
        mStkContext = new StkContext[mSimCount];
        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mStkCmdReceiver = new StkCmdReceiver();
        registerReceiver(mStkCmdReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        for (i = 0; i < mSimCount; i++) {
            CatLog.d(LOG_TAG, "slotId: " + i);
            mStkService[i] = CatService.getInstance(i);
            mStkContext[i] = new StkContext();
            mStkContext[i].mSlotId = i;
            mStkContext[i].mCmdsQ = new LinkedList<DelayedCmd>();
        }

        Thread serviceThread = new Thread(null, this, "Stk App Service");
        serviceThread.start();
        mNotificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        sInstance = this;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (intent == null) {
            CatLog.d(LOG_TAG, "StkAppService onStart intent is null so return");
            return;
        }

        Bundle args = intent.getExtras();
        if (args == null) {
            CatLog.d(LOG_TAG, "StkAppService onStart args is null so return");
            return;
        }

        int op = args.getInt(OPCODE);
        int slotId = 0;
        int i = 0;
        if (op != OP_BOOT_COMPLETED) {
            slotId = args.getInt(SLOT_ID);
        }
        CatLog.d(LOG_TAG, "onStart sim id: " + slotId + ", op: " + op + ", *****");
        if ((slotId >= 0 && slotId < mSimCount) && mStkService[slotId] == null) {
            mStkService[slotId] = CatService.getInstance(slotId);
            if (mStkService[slotId] == null) {
                CatLog.d(LOG_TAG, "mStkService is: " + mStkContext[slotId].mStkServiceState);
                mStkContext[slotId].mStkServiceState = STATE_NOT_EXIST;
                //Check other StkService state.
                //If all StkServices are not available, stop itself and uninstall apk.
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                    if (i != slotId
                            && (mStkContext[i].mStkServiceState == STATE_UNKNOWN
                            || mStkContext[i].mStkServiceState == STATE_EXIST)) {
                       break;
                   }
                }
            } else {
                mStkContext[slotId].mStkServiceState = STATE_EXIST;
            }
            if (i == mSimCount) {
                stopSelf();
                StkAppInstaller.unInstall(mContext);
                return;
            }
        }

        waitForLooper();

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = op;
        msg.arg2 = slotId;
        switch(msg.arg1) {
        case OP_CMD:
            msg.obj = args.getParcelable(CMD_MSG);
            break;
        case OP_RESPONSE:
        case OP_CARD_STATUS_CHANGED:
        case OP_LOCALE_CHANGED:
        case OP_ALPHA_NOTIFY:
        case OP_IDLE_SCREEN:
            msg.obj = args;
            /* falls through */
        case OP_LAUNCH_APP:
        case OP_END_SESSION:
        case OP_BOOT_COMPLETED:
            break;
        default:
            return;
        }
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        CatLog.d(LOG_TAG, "onDestroy()");
        if (mStkCmdReceiver != null) {
            unregisterReceiver(mStkCmdReceiver);
            mStkCmdReceiver = null;
        }
        mPowerManager = null;
        waitForLooper();
        mServiceLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void run() {
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }

    /*
     * Package api used by StkMenuActivity to indicate if its on the foreground.
     */
    void indicateMenuVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mMenuIsVisible = visibility;
        }
    }

    /*
     * Package api used by StkDialogActivity to indicate if its on the foreground.
     */
    void setDisplayTextDlgVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mDisplayTextDlgIsVisibile = visibility;
        }
    }

    boolean isInputPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isInputFinishBySrv: " + mStkContext[slotId].mIsInputPending);
            return mStkContext[slotId].mIsInputPending;
        }
        return false;
    }

    boolean isMenuPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isMenuPending: " + mStkContext[slotId].mIsMenuPending);
            return mStkContext[slotId].mIsMenuPending;
        }
        return false;
    }

    boolean isDialogPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isDialogPending: " + mStkContext[slotId].mIsDialogPending);
            return mStkContext[slotId].mIsDialogPending;
        }
        return false;
    }

    /*
     * Package api used by StkMenuActivity to get its Menu parameter.
     */
    Menu getMenu(int slotId) {
        CatLog.d(LOG_TAG, "StkAppService, getMenu, sim id: " + slotId);
        if (slotId >=0 && slotId < mSimCount) {
            return mStkContext[slotId].mCurrentMenu;
        } else {
            return null;
        }
    }

    /*
     * Package api used by StkMenuActivity to get its Main Menu parameter.
     */
    Menu getMainMenu(int slotId) {
        CatLog.d(LOG_TAG, "StkAppService, getMainMenu, sim id: " + slotId);
        if (slotId >=0 && slotId < mSimCount && (mStkContext[slotId].mMainCmd != null)) {
            return mStkContext[slotId].mMainCmd.getMenu();
        } else {
            return null;
        }
    }

    /*
     * Package api used by UI Activities and Dialogs to communicate directly
     * with the service to deliver state information and parameters.
     */
    static StkAppService getInstance() {
        return sInstance;
    }

    private void waitForLooper() {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private final class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if(null == msg) {
                CatLog.d(LOG_TAG, "ServiceHandler handleMessage msg is null");
                return;
            }
            int opcode = msg.arg1;
            int slotId = msg.arg2;

            CatLog.d(LOG_TAG, "handleMessage opcode[" + opcode + "], sim id[" + slotId + "]");
            if (opcode == OP_CMD && msg.obj != null &&
                    ((CatCmdMessage)msg.obj).getCmdType()!= null) {
                CatLog.d(LOG_TAG, "cmdName[" + ((CatCmdMessage)msg.obj).getCmdType().name() + "]");
            }
            mStkContext[slotId].mOpCode = opcode;
            switch (opcode) {
            case OP_LAUNCH_APP:
                if (mStkContext[slotId].mMainCmd == null) {
                    CatLog.d(LOG_TAG, "mMainCmd is null");
                    // nothing todo when no SET UP MENU command didn't arrive.
                    return;
                }
                CatLog.d(LOG_TAG, "handleMessage OP_LAUNCH_APP - mCmdInProgress[" +
                        mStkContext[slotId].mCmdInProgress + "]");

                //If there is a pending activity for the slot id,
                //just finish it and create a new one to handle the pending command.
                cleanUpInstanceStackBySlot(slotId);

                CatLog.d(LOG_TAG, "Current cmd type: " +
                        mStkContext[slotId].mCurrentCmd.getCmdType());
                //Restore the last command from stack by slot id.
                restoreInstanceFromStackBySlot(slotId);
                break;
            case OP_CMD:
                CatLog.d(LOG_TAG, "[OP_CMD]");
                CatCmdMessage cmdMsg = (CatCmdMessage) msg.obj;
                // There are two types of commands:
                // 1. Interactive - user's response is required.
                // 2. Informative - display a message, no interaction with the user.
                //
                // Informative commands can be handled immediately without any delay.
                // Interactive commands can't override each other. So if a command
                // is already in progress, we need to queue the next command until
                // the user has responded or a timeout expired.
                if (!isCmdInteractive(cmdMsg)) {
                    handleCmd(cmdMsg, slotId);
                } else {
                    if (!mStkContext[slotId].mCmdInProgress) {
                        mStkContext[slotId].mCmdInProgress = true;
                        handleCmd((CatCmdMessage) msg.obj, slotId);
                    } else {
                        CatLog.d(LOG_TAG, "[Interactive][in progress]");
                        mStkContext[slotId].mCmdsQ.addLast(new DelayedCmd(OP_CMD,
                                (CatCmdMessage) msg.obj, slotId));
                    }
                }
                break;
            case OP_RESPONSE:
                handleCmdResponse((Bundle) msg.obj, slotId);
                // call delayed commands if needed.
                if (mStkContext[slotId].mCmdsQ.size() != 0) {
                    callDelayedMsg(slotId);
                } else {
                    mStkContext[slotId].mCmdInProgress = false;
                }
                break;
            case OP_END_SESSION:
                if (!mStkContext[slotId].mCmdInProgress) {
                    mStkContext[slotId].mCmdInProgress = true;
                    handleSessionEnd(slotId);
                } else {
                    mStkContext[slotId].mCmdsQ.addLast(
                            new DelayedCmd(OP_END_SESSION, null, slotId));
                }
                break;
            case OP_BOOT_COMPLETED:
                CatLog.d(LOG_TAG, " OP_BOOT_COMPLETED");
                int i = 0;
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                    if (mStkContext[i].mMainCmd != null) {
                        break;
                    }
                }
                if (i == mSimCount) {
                    StkAppInstaller.unInstall(mContext);
                }
                break;
            case OP_DELAYED_MSG:
                handleDelayedCmd(slotId);
                break;
            case OP_CARD_STATUS_CHANGED:
                CatLog.d(LOG_TAG, "Card/Icc Status change received");
                handleCardStatusChangeAndIccRefresh((Bundle) msg.obj, slotId);
                break;
            case OP_SET_ACT_INST:
                Activity act = new Activity();
                act = (Activity) msg.obj;
                CatLog.d(LOG_TAG, "Set activity instance. " + act);
                mStkContext[slotId].mActivityInstance = act;
                break;
            case OP_SET_DAL_INST:
                Activity dal = new Activity();
                CatLog.d(LOG_TAG, "Set dialog instance. " + dal);
                dal = (Activity) msg.obj;
                mStkContext[slotId].mDialogInstance = dal;
                break;
            case OP_SET_MAINACT_INST:
                Activity mainAct = new Activity();
                mainAct = (Activity) msg.obj;
                CatLog.d(LOG_TAG, "Set activity instance. " + mainAct);
                mStkContext[slotId].mMainActivityInstance = mainAct;
                break;
            case OP_LOCALE_CHANGED:
                CatLog.d(this, "Locale Changed");
                checkForSetupEvent(LANGUAGE_SELECTION_EVENT,(Bundle) msg.obj, slotId);
                break;
            case OP_ALPHA_NOTIFY:
                handleAlphaNotify((Bundle) msg.obj);
                break;
            case OP_IDLE_SCREEN:
               for (int slot = 0; slot < mSimCount; slot++) {
                    if (mStkContext[slot] != null) {
                        handleIdleScreen(slot);
                    }
                }
                break;
            }
        }

        private void handleCardStatusChangeAndIccRefresh(Bundle args, int slotId) {
            boolean cardStatus = args.getBoolean(AppInterface.CARD_STATUS);

            CatLog.d(LOG_TAG, "CardStatus: " + cardStatus);
            if (cardStatus == false) {
                CatLog.d(LOG_TAG, "CARD is ABSENT");
                // Uninstall STKAPP, Clear Idle text, Stop StkAppService
                mNotificationManager.cancel(getNotificationId(slotId));
                if (isAllOtherCardsAbsent(slotId)) {
                    CatLog.d(LOG_TAG, "All CARDs are ABSENT");
                    StkAppInstaller.unInstall(mContext);
                    stopSelf();
                }
            } else {
                IccRefreshResponse state = new IccRefreshResponse();
                state.refreshResult = args.getInt(AppInterface.REFRESH_RESULT);

                CatLog.d(LOG_TAG, "Icc Refresh Result: "+ state.refreshResult);
                if ((state.refreshResult == IccRefreshResponse.REFRESH_RESULT_INIT) ||
                    (state.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET)) {
                    // Clear Idle Text
                    mNotificationManager.cancel(getNotificationId(slotId));
                }

                if (state.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET) {
                    // Uninstall STkmenu
                    if (isAllOtherCardsAbsent(slotId)) {
                        StkAppInstaller.unInstall(mContext);
                    }
                    mStkContext[slotId].mCurrentMenu = null;
                    mStkContext[slotId].mMainCmd = null;
                }
            }
        }
    }
    /*
     * Check if all SIMs are absent except the id of slot equals "slotId".
     */
    private boolean isAllOtherCardsAbsent(int slotId) {
        TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        int i = 0;

        for (i = 0; i < mSimCount; i++) {
            if (i != slotId && mTm.hasIccCard(i)) {
                break;
            }
        }
        if (i == mSimCount) {
            return true;
        } else {
            return false;
        }
    }

    /*
     * If the device is not in an interactive state, we can assume
     * that the screen is idle.
     */
    private boolean isScreenIdle() {
        return (!mPowerManager.isInteractive());
    }

    private void handleIdleScreen(int slotId) {

        // If the idle screen event is present in the list need to send the
        // response to SIM.
        CatLog.d(this, "Need to send IDLE SCREEN Available event to SIM");
        checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null, slotId);

        if (mStkContext[slotId].mIdleModeTextCmd != null) {
           launchIdleText(slotId);
        }
    }

    private void sendScreenBusyResponse(int slotId) {
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }
        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);
        CatLog.d(this, "SCREEN_BUSY");
        resMsg.setResultCode(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        mStkService[slotId].onCmdResponse(resMsg);
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            callDelayedMsg(slotId);
        } else {
            mStkContext[slotId].mCmdInProgress = false;
        }
    }

    private void sendResponse(int resId, int slotId, boolean confirm) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_RESPONSE;
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        args.putInt(SLOT_ID, slotId);
        args.putBoolean(StkAppService.CONFIRMATION, confirm);
        msg.obj = args;
        mServiceHandler.sendMessage(msg);
    }

    private boolean isCmdInteractive(CatCmdMessage cmd) {
        switch (cmd.getCmdType()) {
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
        case SET_UP_IDLE_MODE_TEXT:
        case SET_UP_MENU:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
        case SET_UP_EVENT_LIST:
            return false;
        }

        return true;
    }

    private void handleDelayedCmd(int slotId) {
        CatLog.d(LOG_TAG, "handleDelayedCmd, slotId: " + slotId);
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            DelayedCmd cmd = mStkContext[slotId].mCmdsQ.poll();
            if (cmd != null) {
                CatLog.d(LOG_TAG, "handleDelayedCmd - queue size: " +
                        mStkContext[slotId].mCmdsQ.size() +
                        " id: " + cmd.id + "sim id: " + cmd.slotId);
                switch (cmd.id) {
                case OP_CMD:
                    handleCmd(cmd.msg, cmd.slotId);
                    break;
                case OP_END_SESSION:
                    handleSessionEnd(cmd.slotId);
                    break;
                }
            }
        }
    }

    private void callDelayedMsg(int slotId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_DELAYED_MSG;
        msg.arg2 = slotId;
        mServiceHandler.sendMessage(msg);
    }

    private void callSetActivityInstMsg(int inst_type, int slotId, Object obj) {
        Message msg = mServiceHandler.obtainMessage();
        msg.obj = obj;
        msg.arg1 = inst_type;
        msg.arg2 = slotId;
        mServiceHandler.sendMessage(msg);
    }

    private void handleSessionEnd(int slotId) {
        // We should finish all pending activity if receiving END SESSION command.
        cleanUpInstanceStackBySlot(slotId);

        mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
        CatLog.d(LOG_TAG, "[handleSessionEnd] - mCurrentCmd changed to mMainCmd!.");
        mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mMainCmd;
        CatLog.d(LOG_TAG, "slotId: " + slotId + ", mMenuState: " +
                mStkContext[slotId].mMenuState);

        mStkContext[slotId].mIsInputPending = false;
        mStkContext[slotId].mIsMenuPending = false;
        mStkContext[slotId].mIsDialogPending = false;

        if (mStkContext[slotId].mMainCmd == null) {
            CatLog.d(LOG_TAG, "[handleSessionEnd][mMainCmd is null!]");
        }
        mStkContext[slotId].lastSelectedItem = null;
        // In case of SET UP MENU command which removed the app, don't
        // update the current menu member.
        if (mStkContext[slotId].mCurrentMenu != null && mStkContext[slotId].mMainCmd != null) {
            mStkContext[slotId].mCurrentMenu = mStkContext[slotId].mMainCmd.getMenu();
        }
        CatLog.d(LOG_TAG, "[handleSessionEnd][mMenuState]" + mStkContext[slotId].mMenuIsVisible);
        // In mutiple instance architecture, the main menu for slotId will be finished when user
        // goes to the Stk menu of the other SIM. So, we should launch a new instance for the
        // main menu if the main menu instance has been finished.
        // If the current menu is secondary menu, we should launch main menu.
        if (StkMenuActivity.STATE_SECONDARY == mStkContext[slotId].mMenuState) {
            launchMenuActivity(null, slotId);
        }
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            callDelayedMsg(slotId);
        } else {
            mStkContext[slotId].mCmdInProgress = false;
        }
        // In case a launch browser command was just confirmed, launch that url.
        if (mStkContext[slotId].launchBrowser) {
            mStkContext[slotId].launchBrowser = false;
            launchBrowser(mStkContext[slotId].mBrowserSettings);
        }
    }

    // returns true if any Stk related activity already has focus on the screen
    private boolean isTopOfStack() {
        ActivityManager mAcivityManager = (ActivityManager) mContext
                .getSystemService(ACTIVITY_SERVICE);
        String currentPackageName = mAcivityManager.getRunningTasks(1).get(0).topActivity
                .getPackageName();
        if (null != currentPackageName) {
            return currentPackageName.equals(PACKAGE_NAME);
        }

        return false;
    }

    private void handleCmd(CatCmdMessage cmdMsg, int slotId) {

        if (cmdMsg == null) {
            return;
        }
        // save local reference for state tracking.
        mStkContext[slotId].mCurrentCmd = cmdMsg;
        boolean waitForUsersResponse = true;

        mStkContext[slotId].mIsInputPending = false;
        mStkContext[slotId].mIsMenuPending = false;
        mStkContext[slotId].mIsDialogPending = false;

        CatLog.d(LOG_TAG,"[handleCmd]" + cmdMsg.getCmdType().name());
        switch (cmdMsg.getCmdType()) {
        case DISPLAY_TEXT:
            TextMessage msg = cmdMsg.geTextMessage();
            waitForUsersResponse = msg.responseNeeded;
            if (mStkContext[slotId].lastSelectedItem != null) {
                msg.title = mStkContext[slotId].lastSelectedItem;
            } else if (mStkContext[slotId].mMainCmd != null){
                msg.title = mStkContext[slotId].mMainCmd.getMenu().title;
            } else {
                // TODO: get the carrier name from the SIM
                msg.title = "";
            }
            //If we receive a low priority Display Text and the device is
            // not displaying any STK related activity and the screen is not idle
            // ( that is, device is in an interactive state), then send a screen busy
            // terminal response. Otherwise display the message. The existing
            // displayed message shall be updated with the new display text
            // proactive command (Refer to ETSI TS 102 384 section 27.22.4.1.4.4.2).
            if (!(msg.isHighPriority || mStkContext[slotId].mMenuIsVisible
                    || mStkContext[slotId].mDisplayTextDlgIsVisibile || isTopOfStack())) {
                if(!isScreenIdle()) {
                    CatLog.d(LOG_TAG, "Screen is not idle");
                    sendScreenBusyResponse(slotId);
                } else {
                    launchTextDialog(slotId);
                }
            } else {
                launchTextDialog(slotId);
            }
            break;
        case SELECT_ITEM:
            CatLog.d(LOG_TAG, "SELECT_ITEM +");
            mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenu = cmdMsg.getMenu();
            launchMenuActivity(cmdMsg.getMenu(), slotId);
            break;
        case SET_UP_MENU:
            mStkContext[slotId].mCmdInProgress = false;
            mStkContext[slotId].mMainCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenu = cmdMsg.getMenu();
            CatLog.d(LOG_TAG, "SET_UP_MENU [" + removeMenu(slotId) + "]");

            if (removeMenu(slotId)) {
                int i = 0;
                CatLog.d(LOG_TAG, "removeMenu() - Uninstall App");
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
                //Check other setup menu state. If all setup menu are removed, uninstall apk.
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                    if (i != slotId
                            && (mStkContext[slotId].mSetupMenuState == STATE_UNKNOWN
                            || mStkContext[slotId].mSetupMenuState == STATE_EXIST)) {
                        CatLog.d(LOG_TAG, "Not Uninstall App:" + i + ","
                                + mStkContext[slotId].mSetupMenuState);
                        break;
                    }
                }
                if (i == mSimCount) {
                    StkAppInstaller.unInstall(mContext);
                }
            } else {
                CatLog.d(LOG_TAG, "install App");
                StkAppInstaller.install(mContext);
            }
            if (mStkContext[slotId].mMenuIsVisible) {
                launchMenuActivity(null, slotId);
            }
            break;
        case GET_INPUT:
        case GET_INKEY:
            launchInputActivity(slotId);
            break;
        case SET_UP_IDLE_MODE_TEXT:
            waitForUsersResponse = false;
            mStkContext[slotId].mIdleModeTextCmd = mStkContext[slotId].mCurrentCmd;
            TextMessage idleModeText = mStkContext[slotId].mCurrentCmd.geTextMessage();
            if (idleModeText == null) {
                launchIdleText(slotId);
                mStkContext[slotId].mIdleModeTextCmd = null;
            }
            mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
            if ((mStkContext[slotId].mIdleModeTextCmd != null) && isScreenIdle()) {
                CatLog.d(this, "set up idle mode");
                launchIdleText(slotId);
            }
            break;
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
        case GET_CHANNEL_STATUS:
            waitForUsersResponse = false;
            launchEventMessage(slotId);
            break;
        case LAUNCH_BROWSER:
            launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.geTextMessage(), slotId);
            break;
        case SET_UP_CALL:
            TextMessage mesg = mStkContext[slotId].mCurrentCmd.getCallSettings().confirmMsg;
            if((mesg != null) && (mesg.text == null || mesg.text.length() == 0)) {
                mesg.text = getResources().getString(R.string.default_setup_call_msg);
            }
            CatLog.d(this, "SET_UP_CALL mesg.text " + mesg.text);
            launchConfirmationDialog(mesg, slotId);
            break;
        case PLAY_TONE:
            launchToneDialog(slotId);
            break;
        case OPEN_CHANNEL:
            launchOpenChannelDialog(slotId);
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            TextMessage m = mStkContext[slotId].mCurrentCmd.geTextMessage();

            if ((m != null) && (m.text == null)) {
                switch(cmdMsg.getCmdType()) {
                case CLOSE_CHANNEL:
                    m.text = getResources().getString(R.string.default_close_channel_msg);
                    break;
                case RECEIVE_DATA:
                    m.text = getResources().getString(R.string.default_receive_data_msg);
                    break;
                case SEND_DATA:
                    m.text = getResources().getString(R.string.default_send_data_msg);
                    break;
                }
            }
            /*
             * Display indication in the form of a toast to the user if required.
             */
            launchEventMessage(slotId);
            break;
        case SET_UP_EVENT_LIST:
            mStkContext[slotId].mSetupEventListSettings =
                    mStkContext[slotId].mCurrentCmd.getSetEventList();
            mStkContext[slotId].mCurrentSetupEventCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
            if (isScreenIdle()) {
                CatLog.d(this," Check if IDLE_SCREEN_AVAILABLE_EVENT is present in List");
                checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null, slotId);
            }
            break;
        }

        if (!waitForUsersResponse) {
            if (mStkContext[slotId].mCmdsQ.size() != 0) {
                callDelayedMsg(slotId);
            } else {
                mStkContext[slotId].mCmdInProgress = false;
            }
        }
    }

    private void handleCmdResponse(Bundle args, int slotId) {
        CatLog.d(LOG_TAG, "handleCmdResponse, sim id: " + slotId);
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }

        if (mStkService[slotId] == null) {
            mStkService[slotId] = CatService.getInstance(slotId);
            if (mStkService[slotId] == null) {
                // This should never happen (we should be responding only to a message
                // that arrived from StkService). It has to exist by this time
                CatLog.d(LOG_TAG, "Exception! mStkService is null when we need to send response.");
                throw new RuntimeException("mStkService is null when we need to send response");
            }
        }

        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);

        // set result code
        boolean helpRequired = args.getBoolean(HELP, false);
        boolean confirmed    = false;

        switch(args.getInt(RES_ID)) {
        case RES_ID_MENU_SELECTION:
            CatLog.d(LOG_TAG, "MENU_SELECTION=" + mStkContext[slotId].
                    mCurrentMenuCmd.getCmdType());
            int menuSelection = args.getInt(MENU_SELECTION);
            switch(mStkContext[slotId].mCurrentMenuCmd.getCmdType()) {
            case SET_UP_MENU:
            case SELECT_ITEM:
                mStkContext[slotId].lastSelectedItem = getItemName(menuSelection, slotId);
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(ResultCode.OK);
                }
                resMsg.setMenuSelection(menuSelection);
                break;
            }
            break;
        case RES_ID_INPUT:
            CatLog.d(LOG_TAG, "RES_ID_INPUT");
            String input = args.getString(INPUT);
            if (input != null && (null != mStkContext[slotId].mCurrentCmd.geInput()) &&
                    (mStkContext[slotId].mCurrentCmd.geInput().yesNo)) {
                boolean yesNoSelection = input
                        .equals(StkInputActivity.YES_STR_RESPONSE);
                resMsg.setYesNo(yesNoSelection);
            } else {
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(ResultCode.OK);
                    resMsg.setInput(input);
                }
            }
            break;
        case RES_ID_CONFIRM:
            CatLog.d(this, "RES_ID_CONFIRM");
            confirmed = args.getBoolean(CONFIRMATION);
            switch (mStkContext[slotId].mCurrentCmd.getCmdType()) {
            case DISPLAY_TEXT:
                resMsg.setResultCode(confirmed ? ResultCode.OK
                    : ResultCode.UICC_SESSION_TERM_BY_USER);
                break;
            case LAUNCH_BROWSER:
                resMsg.setResultCode(confirmed ? ResultCode.OK
                        : ResultCode.UICC_SESSION_TERM_BY_USER);
                if (confirmed) {
                    mStkContext[slotId].launchBrowser = true;
                    mStkContext[slotId].mBrowserSettings =
                            mStkContext[slotId].mCurrentCmd.getBrowserSettings();
                }
                break;
            case SET_UP_CALL:
                resMsg.setResultCode(ResultCode.OK);
                resMsg.setConfirmation(confirmed);
                if (confirmed) {
                    CatLog.d(this, "Going back to mainMenu before starting a call.");
                    launchMenuActivity(null, slotId);
                    launchEventMessage(slotId,
                            mStkContext[slotId].mCurrentCmd.getCallSettings().callMsg);
                }
                break;
            }
            break;
        case RES_ID_DONE:
            resMsg.setResultCode(ResultCode.OK);
            break;
        case RES_ID_BACKWARD:
            CatLog.d(LOG_TAG, "RES_ID_BACKWARD");
            resMsg.setResultCode(ResultCode.BACKWARD_MOVE_BY_USER);
            break;
        case RES_ID_END_SESSION:
            CatLog.d(LOG_TAG, "RES_ID_END_SESSION");
            resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
            break;
        case RES_ID_TIMEOUT:
            CatLog.d(LOG_TAG, "RES_ID_TIMEOUT");
            // GCF test-case 27.22.4.1.1 Expected Sequence 1.5 (DISPLAY TEXT,
            // Clear message after delay, successful) expects result code OK.
            // If the command qualifier specifies no user response is required
            // then send OK instead of NO_RESPONSE_FROM_USER
            if ((mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.DISPLAY_TEXT.value())
                    && (mStkContext[slotId].mCurrentCmd.geTextMessage().userClear == false)) {
                resMsg.setResultCode(ResultCode.OK);
            } else {
                resMsg.setResultCode(ResultCode.NO_RESPONSE_FROM_USER);
            }
            break;
        case RES_ID_CHOICE:
            int choice = args.getInt(CHOICE);
            CatLog.d(this, "User Choice=" + choice);
            switch (choice) {
                case YES:
                    resMsg.setResultCode(ResultCode.OK);
                    confirmed = true;
                    break;
                case NO:
                    resMsg.setResultCode(ResultCode.USER_NOT_ACCEPT);
                    break;
            }

            if (mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.OPEN_CHANNEL.value()) {
                resMsg.setConfirmation(confirmed);
            }
            break;

        default:
            CatLog.d(LOG_TAG, "Unknown result id");
            return;
        }

        if (null != mStkContext[slotId].mCurrentCmd &&
                null != mStkContext[slotId].mCurrentCmd.getCmdType()) {
            CatLog.d(LOG_TAG, "handleCmdResponse- cmdName[" +
                    mStkContext[slotId].mCurrentCmd.getCmdType().name() + "]");
        }
        mStkService[slotId].onCmdResponse(resMsg);
    }

    /**
     * Returns 0 or FLAG_ACTIVITY_NO_USER_ACTION, 0 means the user initiated the action.
     *
     * @param userAction If the userAction is yes then we always return 0 otherwise
     * mMenuIsVisible is used to determine what to return. If mMenuIsVisible is true
     * then we are the foreground app and we'll return 0 as from our perspective a
     * user action did cause. If it's false than we aren't the foreground app and
     * FLAG_ACTIVITY_NO_USER_ACTION is returned.
     *
     * @return 0 or FLAG_ACTIVITY_NO_USER_ACTION
     */
    private int getFlagActivityNoUserAction(InitiatedByUserAction userAction, int slotId) {
        return ((userAction == InitiatedByUserAction.yes) | mStkContext[slotId].mMenuIsVisible)
                ? 0 : Intent.FLAG_ACTIVITY_NO_USER_ACTION;
    }
    /**
     * This method is used for cleaning up pending instances in stack.
     */
    private void cleanUpInstanceStackBySlot(int slotId) {
        Activity activity = mStkContext[slotId].getPendingActivityInstance();
        Activity dialog = mStkContext[slotId].getPendingDialogInstance();
        CatLog.d(LOG_TAG, "cleanUpInstanceStackBySlot slotId: " + slotId);
        if (mStkContext[slotId].mCurrentCmd == null) {
            CatLog.d(LOG_TAG, "current cmd is null.");
            return;
        }
        if (activity != null) {
            CatLog.d(LOG_TAG, "current cmd type: " +
                    mStkContext[slotId].mCurrentCmd.getCmdType());
            if (mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.GET_INPUT.value() ||
                    mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.GET_INKEY.value()) {
                mStkContext[slotId].mIsInputPending = true;
            } else if (mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.SET_UP_MENU.value() ||
                    mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.SELECT_ITEM.value()) {
                mStkContext[slotId].mIsMenuPending = true;
            } else {
            }
            CatLog.d(LOG_TAG, "finish pending activity.");
            activity.finish();
            mStkContext[slotId].mActivityInstance = null;
        }
        if (dialog != null) {
            CatLog.d(LOG_TAG, "finish pending dialog.");
            mStkContext[slotId].mIsDialogPending = true;
            dialog.finish();
            mStkContext[slotId].mDialogInstance = null;
        }
    }
    /**
     * This method is used for restoring pending instances from stack.
     */
    private void restoreInstanceFromStackBySlot(int slotId) {
        AppInterface.CommandType cmdType = mStkContext[slotId].mCurrentCmd.getCmdType();

        CatLog.d(LOG_TAG, "restoreInstanceFromStackBySlot cmdType : " + cmdType);
        switch(cmdType) {
            case GET_INPUT:
            case GET_INKEY:
                launchInputActivity(slotId);
                //Set mMenuIsVisible to true for showing main menu for
                //following session end command.
                mStkContext[slotId].mMenuIsVisible = true;
            break;
            case DISPLAY_TEXT:
                launchTextDialog(slotId);
            break;
            case LAUNCH_BROWSER:
                launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.geTextMessage(),
                        slotId);
            break;
            case OPEN_CHANNEL:
                launchOpenChannelDialog(slotId);
            break;
            case SET_UP_CALL:
                launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.getCallSettings().
                        confirmMsg, slotId);
            break;
            case SET_UP_MENU:
            case SELECT_ITEM:
                launchMenuActivity(null, slotId);
            break;
        default:
            break;
        }
    }

    private void launchMenuActivity(Menu menu, int slotId) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        String targetActivity = STK_MENU_ACTIVITY_NAME;
        String uriString = STK_MENU_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);

        CatLog.d(LOG_TAG, "launchMenuActivity, slotId: " + slotId + " , " +
                uriData.toString() + " , " + mStkContext[slotId].mOpCode + ", "
                + mStkContext[slotId].mMenuState);
        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        int intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK;

        if (menu == null) {
            // We assume this was initiated by the user pressing the tool kit icon
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.yes, slotId);
            if (mStkContext[slotId].mOpCode == OP_END_SESSION) {
                CatLog.d(LOG_TAG, "launchMenuActivity, return OP_END_SESSION");
                mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
                if (mStkContext[slotId].mMainActivityInstance != null) {
                    CatLog.d(LOG_TAG, "launchMenuActivity, mMainActivityInstance is not null");
                    return;
                }
            }

            //If the last pending menu is secondary menu, "STATE" should be "STATE_SECONDARY".
            //Otherwise, it should be "STATE_MAIN".
            if (mStkContext[slotId].mOpCode == OP_LAUNCH_APP &&
                    mStkContext[slotId].mMenuState == StkMenuActivity.STATE_SECONDARY) {
                newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
            } else {
                newIntent.putExtra("STATE", StkMenuActivity.STATE_MAIN);
                mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
            }
        } else {
            // We don't know and we'll let getFlagActivityNoUserAction decide.
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId);
            newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
            mStkContext[slotId].mMenuState = StkMenuActivity.STATE_SECONDARY;
        }
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        newIntent.setFlags(intentFlags);
        mContext.startActivity(newIntent);
    }

    private void launchInputActivity(int slotId) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        String targetActivity = STK_INPUT_ACTIVITY_NAME;
        String uriString = STK_INPUT_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);

        CatLog.d(LOG_TAG, "launchInputActivity, slotId: " + slotId);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        newIntent.putExtra("INPUT", mStkContext[slotId].mCurrentCmd.geInput());
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        mContext.startActivity(newIntent);
    }

    private void launchTextDialog(int slotId) {
        CatLog.d(LOG_TAG, "launchTextDialog, slotId: " + slotId);
        Intent newIntent = new Intent();
        String targetActivity = STK_DIALOG_ACTIVITY_NAME;
        int action = getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId);
        String uriString = STK_DIALOG_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);
        if (newIntent != null) {
            newIntent.setClassName(PACKAGE_NAME, targetActivity);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
            newIntent.setData(uriData);
            newIntent.putExtra("TEXT", mStkContext[slotId].mCurrentCmd.geTextMessage());
            newIntent.putExtra(SLOT_ID, slotId);
            startActivity(newIntent);
            // For display texts with immediate response, send the terminal response
            // immediately. responseNeeded will be false, if display text command has
            // the immediate response tlv.
            if (!mStkContext[slotId].mCurrentCmd.geTextMessage().responseNeeded) {
                sendResponse(RES_ID_CONFIRM, slotId, true);
            }
        }
    }

    public boolean isStkDialogActivated(Context context) {
        String stkDialogActivity = "com.android.stk.StkDialogActivity";
        boolean activated = false;
        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        String topActivity = am.getRunningTasks(1).get(0).topActivity.getClassName();

        CatLog.d(LOG_TAG, "isStkDialogActivated: " + topActivity);
        if (topActivity.equals(stkDialogActivity)) {
            activated = true;
        }
        CatLog.d(LOG_TAG, "activated : " + activated);
        return activated;
    }

    private void sendSetUpEventResponse(int event, byte[] addedInfo, int slotId) {
        CatLog.d(this, "sendSetUpEventResponse: event : " + event + "slotId = " + slotId);

        if (mStkContext[slotId].mCurrentSetupEventCmd == null){
            CatLog.e(this, "mCurrentSetupEventCmd is null");
            return;
        }

        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentSetupEventCmd);

        resMsg.setResultCode(ResultCode.OK);
        resMsg.setEventDownload(event, addedInfo);

        mStkService[slotId].onCmdResponse(resMsg);
    }

    private void checkForSetupEvent(int event, Bundle args, int slotId) {
        boolean eventPresent = false;
        byte[] addedInfo = null;
        CatLog.d(this, "Event :" + event);

        if (mStkContext[slotId].mSetupEventListSettings != null) {
            /* Checks if the event is present in the EventList updated by last
             * SetupEventList Proactive Command */
            for (int i : mStkContext[slotId].mSetupEventListSettings.eventList) {
                 if (event == i) {
                     eventPresent =  true;
                     break;
                 }
            }

            /* If Event is present send the response to ICC */
            if (eventPresent == true) {
                CatLog.d(this, " Event " + event + "exists in the EventList");

                switch (event) {
                    case IDLE_SCREEN_AVAILABLE_EVENT:
                        sendSetUpEventResponse(event, addedInfo, slotId);
                        removeSetUpEvent(event, slotId);
                        break;
                    case LANGUAGE_SELECTION_EVENT:
                        String language =  mContext
                                .getResources().getConfiguration().locale.getLanguage();
                        CatLog.d(this, "language: " + language);
                        // Each language code is a pair of alpha-numeric characters.
                        // Each alpha-numeric character shall be coded on one byte
                        // using the SMS default 7-bit coded alphabet
                        addedInfo = GsmAlphabet.stringToGsm8BitPacked(language);
                        sendSetUpEventResponse(event, addedInfo, slotId);
                        break;
                    default:
                        break;
                }
            } else {
                CatLog.e(this, " Event does not exist in the EventList");
            }
        } else {
            CatLog.e(this, "SetupEventList is not received. Ignoring the event: " + event);
        }
    }

    private void  removeSetUpEvent(int event, int slotId) {
        CatLog.d(this, "Remove Event :" + event);

        if (mStkContext[slotId].mSetupEventListSettings != null) {
            /*
             * Make new  Eventlist without the event
             */
            for (int i = 0; i < mStkContext[slotId].mSetupEventListSettings.eventList.length; i++) {
                if (event == mStkContext[slotId].mSetupEventListSettings.eventList[i]) {
                    mStkContext[slotId].mSetupEventListSettings.eventList[i] = INVALID_SETUP_EVENT;
                    break;
                }
            }
        }
    }

    private void launchEventMessage(int slotId) {
        launchEventMessage(slotId, mStkContext[slotId].mCurrentCmd.geTextMessage());
    }

    private void launchEventMessage(int slotId, TextMessage msg) {
        if (msg == null || (msg.text != null && msg.text.length() == 0)) {
            CatLog.d(LOG_TAG, "launchEventMessage return");
            return;
        }

        Toast toast = new Toast(mContext.getApplicationContext());
        LayoutInflater inflate = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(R.layout.stk_event_msg, null);
        TextView tv = (TextView) v
                .findViewById(com.android.internal.R.id.message);
        ImageView iv = (ImageView) v
                .findViewById(com.android.internal.R.id.icon);
        if (msg.icon != null) {
            iv.setImageBitmap(msg.icon);
        } else {
            iv.setVisibility(View.GONE);
        }
        if (!msg.iconSelfExplanatory) {
            tv.setText(msg.text);
        }

        toast.setView(v);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    private void launchConfirmationDialog(TextMessage msg, int slotId) {
        msg.title = mStkContext[slotId].lastSelectedItem;
        Intent newIntent = new Intent();
        String targetActivity = STK_DIALOG_ACTIVITY_NAME;
        String uriString = STK_DIALOG_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);

        if (newIntent != null) {
            newIntent.setClassName(this, targetActivity);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
            newIntent.putExtra("TEXT", msg);
            newIntent.putExtra(SLOT_ID, slotId);
            newIntent.setData(uriData);
            startActivity(newIntent);
        }
    }

    private void launchBrowser(BrowserSettings settings) {
        if (settings == null) {
            return;
        }

        Intent intent = null;
        Uri data = null;

        if (settings.url != null) {
            CatLog.d(LOG_TAG, "settings.url = " + settings.url);
            if ((settings.url.startsWith("http://") || (settings.url.startsWith("https://")))) {
                data = Uri.parse(settings.url);
            } else {
                String modifiedUrl = "http://" + settings.url;
                CatLog.d(LOG_TAG, "modifiedUrl = " + modifiedUrl);
                data = Uri.parse(modifiedUrl);
            }
        }
        if (data != null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(data);
        } else {
            // if the command did not contain a URL,
            // launch the browser to the default homepage.
            CatLog.d(LOG_TAG, "launch browser with default URL ");
            intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_BROWSER);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        switch (settings.mode) {
        case USE_EXISTING_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        case LAUNCH_NEW_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            break;
        case LAUNCH_IF_NOT_ALREADY_LAUNCHED:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        }
        // start browser activity
        startActivity(intent);
        // a small delay, let the browser start, before processing the next command.
        // this is good for scenarios where a related DISPLAY TEXT command is
        // followed immediately.
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {}
    }

    private void launchIdleText(int slotId) {
        TextMessage msg = mStkContext[slotId].mIdleModeTextCmd.geTextMessage();

        if (msg == null || msg.text ==null) {
            CatLog.d(LOG_TAG,  msg == null ? "mCurrent.getTextMessage is NULL"
                    : "mCurrent.getTextMessage.text is NULL");
            mNotificationManager.cancel(getNotificationId(slotId));
            return;
        } else {
            CatLog.d(LOG_TAG, "launchIdleText - text[" + msg.text
                    + "] iconSelfExplanatory[" + msg.iconSelfExplanatory
                    + "] icon[" + msg.icon + "], sim id: " + slotId);
            CatLog.d(LOG_TAG, "Add IdleMode text");
            PendingIntent pendingIntent = PendingIntent.getService(mContext, 0,
                    new Intent(mContext, StkAppService.class), 0);

            final Notification.Builder notificationBuilder = new Notification.Builder(
                    StkAppService.this);
            if (mStkContext[slotId].mMainCmd != null &&
                    mStkContext[slotId].mMainCmd.getMenu() != null) {
                notificationBuilder.setContentTitle(mStkContext[slotId].mMainCmd.getMenu().title);
            } else {
                notificationBuilder.setContentTitle("");
            }
            notificationBuilder
                    .setSmallIcon(com.android.internal.R.drawable.stat_notify_sim_toolkit);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setOngoing(true);
            // Set text and icon for the status bar and notification body.
            if (!msg.iconSelfExplanatory) {
                notificationBuilder.setContentText(msg.text);
                notificationBuilder.setTicker(msg.text);
            }
            if (msg.icon != null) {
                notificationBuilder.setLargeIcon(msg.icon);
            } else {
                Bitmap bitmapIcon = BitmapFactory.decodeResource(StkAppService.this
                    .getResources().getSystem(),
                    com.android.internal.R.drawable.stat_notify_sim_toolkit);
                notificationBuilder.setLargeIcon(bitmapIcon);
            }
            notificationBuilder.setColor(mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color));
            mNotificationManager.notify(getNotificationId(slotId), notificationBuilder.build());
        }
    }

    private void launchToneDialog(int slotId) {
        Intent newIntent = new Intent(this, ToneDialog.class);
        String uriString = STK_TONE_URI + slotId;
        Uri uriData = Uri.parse(uriString);
        //Set unique URI to create a new instance of activity for different slotId.
        CatLog.d(LOG_TAG, "launchToneDialog, slotId: " + slotId);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.putExtra("TEXT", mStkContext[slotId].mCurrentCmd.geTextMessage());
        newIntent.putExtra("TONE", mStkContext[slotId].mCurrentCmd.getToneSettings());
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        startActivity(newIntent);
    }

    private void launchOpenChannelDialog(int slotId) {
        TextMessage msg = mStkContext[slotId].mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(LOG_TAG, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);
        if (msg.text == null) {
            msg.text = getResources().getString(R.string.default_open_channel_msg);
        }

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(R.string.stk_dialog_accept),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, YES);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .setNegativeButton(getResources().getString(R.string.stk_dialog_reject),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, NO);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private void launchTransientEventMessage(int slotId) {
        TextMessage msg = mStkContext[slotId].mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(LOG_TAG, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(android.R.string.ok),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private int getNotificationId(int slotId) {
        int notifyId = STK_NOTIFICATION_ID;
        if (slotId >= 0 && slotId < mSimCount) {
            notifyId += slotId;
        } else {
            CatLog.d(LOG_TAG, "invalid slotId: " + slotId);
        }
        CatLog.d(LOG_TAG, "getNotificationId, slotId: " + slotId + ", notifyId: " + notifyId);
        return notifyId;
    }

    private String getItemName(int itemId, int slotId) {
        Menu menu = mStkContext[slotId].mCurrentCmd.getMenu();
        if (menu == null) {
            return null;
        }
        for (Item item : menu.items) {
            if (item.id == itemId) {
                return item.text;
            }
        }
        return null;
    }

    private boolean removeMenu(int slotId) {
        try {
            if (mStkContext[slotId].mCurrentMenu.items.size() == 1 &&
                mStkContext[slotId].mCurrentMenu.items.get(0) == null) {
                mStkContext[slotId].mSetupMenuState = STATE_NOT_EXIST;
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(LOG_TAG, "Unable to get Menu's items size");
            mStkContext[slotId].mSetupMenuState = STATE_NOT_EXIST;
            return true;
        }
        mStkContext[slotId].mSetupMenuState = STATE_EXIST;
        return false;
    }

    StkContext getStkContext(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            return mStkContext[slotId];
        } else {
            CatLog.d(LOG_TAG, "invalid slotId: " + slotId);
            return null;
        }
    }

    private void handleAlphaNotify(Bundle args) {
        String alphaString = args.getString(AppInterface.ALPHA_STRING);

        CatLog.d(this, "Alpha string received from card: " + alphaString);
        Toast toast = Toast.makeText(sInstance, alphaString, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
    }
}
