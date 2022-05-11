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

import static com.android.internal.telephony.cat.CatCmdMessage.SetupEventListConstants.IDLE_SCREEN_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.SetupEventListConstants.LANGUAGE_SELECTION_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.SetupEventListConstants.USER_ACTIVITY_EVENT;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.AlertDialog;
import android.app.HomeVisibilityListener;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConfigurationManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CatCmdMessage.BrowserSettings;
import com.android.internal.telephony.cat.CatCmdMessage.SetupEventListSettings;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.cat.Input;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.ResultCode;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.cat.ToneSettings;
import com.android.internal.telephony.uicc.IccRefreshResponse;

import java.util.LinkedList;
import java.util.List;

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
        protected boolean mNotificationOnKeyguard = false;
        protected boolean mNoResponseFromUser = false;
        protected boolean launchBrowser = false;
        protected BrowserSettings mBrowserSettings = null;
        protected LinkedList<DelayedCmd> mCmdsQ = null;
        protected boolean mCmdInProgress = false;
        protected int mStkServiceState = STATE_UNKNOWN;
        protected int mMenuState = StkMenuActivity.STATE_INIT;
        protected int mOpCode = -1;
        private Activity mActivityInstance = null;
        private Activity mDialogInstance = null;
        private Activity mImmediateDialogInstance = null;
        private int mSlotId = 0;
        private SetupEventListSettings mSetupEventListSettings = null;
        private boolean mClearSelectItem = false;
        private boolean mDisplayTextDlgIsVisibile = false;
        private CatCmdMessage mCurrentSetupEventCmd = null;
        private CatCmdMessage mIdleModeTextCmd = null;
        private boolean mIdleModeTextVisible = false;
        // Determins whether the current session was initiated by user operation.
        protected boolean mIsSessionFromUser = false;
        final synchronized void setPendingActivityInstance(Activity act) {
            CatLog.d(LOG_TAG, "setPendingActivityInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_ACT_INST, mSlotId, act);
        }
        final synchronized Activity getPendingActivityInstance() {
            CatLog.d(LOG_TAG, "getPendingActivityInstance act : " + mSlotId + ", " +
                    mActivityInstance);
            return mActivityInstance;
        }
        final synchronized void setPendingDialogInstance(Activity act) {
            CatLog.d(LOG_TAG, "setPendingDialogInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_DAL_INST, mSlotId, act);
        }
        final synchronized Activity getPendingDialogInstance() {
            CatLog.d(LOG_TAG, "getPendingDialogInstance act : " + mSlotId + ", " +
                    mDialogInstance);
            return mDialogInstance;
        }
        final synchronized void setImmediateDialogInstance(Activity act) {
            CatLog.d(LOG_TAG, "setImmediateDialogInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_IMMED_DAL_INST, mSlotId, act);
        }
        final synchronized Activity getImmediateDialogInstance() {
            CatLog.d(LOG_TAG, "getImmediateDialogInstance act : " + mSlotId + ", " +
                    mImmediateDialogInstance);
            return mImmediateDialogInstance;
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
    private HomeVisibilityListener mHomeVisibilityListener = null;
    private BroadcastReceiver mLocaleChangeReceiver = null;
    private TonePlayer mTonePlayer = null;
    private Vibrator mVibrator = null;
    private BroadcastReceiver mUserActivityReceiver = null;

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
    static final String FINISH_TONE_ACTIVITY_ACTION =
                                "android.intent.action.stk.finish_activity";

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
    static final int OP_LOCALE_CHANGED = 10;
    static final int OP_ALPHA_NOTIFY = 11;
    static final int OP_IDLE_SCREEN = 12;
    static final int OP_SET_IMMED_DAL_INST = 13;
    static final int OP_HOME_KEY_PRESSED = 14;

    //Invalid SetupEvent
    static final int INVALID_SETUP_EVENT = 0xFF;

    // Message id to signal stop tone due to play tone timeout.
    private static final int OP_STOP_TONE = 16;

    // Message id to signal stop tone on user keyback.
    static final int OP_STOP_TONE_USER = 17;

    // Message id to send user activity event to card.
    private static final int OP_USER_ACTIVITY = 20;

    // Message id that multi-SIM config has changed (ss <-> ds).
    private static final int EVENT_MULTI_SIM_CONFIG_CHANGED = 21;

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
    static final int RES_ID_ERROR = 24;

    static final int YES = 1;
    static final int NO = 0;

    static final int STATE_UNKNOWN = -1;
    static final int STATE_NOT_EXIST = 0;
    static final int STATE_EXIST = 1;

    private static final Integer PLAY_TONE_ONLY = 0;
    private static final Integer PLAY_TONE_WITH_DIALOG = 1;

    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String STK_MENU_ACTIVITY_NAME = PACKAGE_NAME + ".StkMenuActivity";
    private static final String STK_INPUT_ACTIVITY_NAME = PACKAGE_NAME + ".StkInputActivity";
    private static final String STK_DIALOG_ACTIVITY_NAME = PACKAGE_NAME + ".StkDialogActivity";
    // Notification id used to display Idle Mode text in NotificationManager.
    private static final int STK_NOTIFICATION_ID = 333;
    // Notification channel containing all mobile service messages notifications.
    private static final String STK_NOTIFICATION_CHANNEL_ID = "mobileServiceMessages";

    private static final String LOG_TAG =
            new Object(){}.getClass().getEnclosingClass().getSimpleName();

    static final String SESSION_ENDED = "session_ended";

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

    // system property to set the STK specific default url for launch browser proactive cmds
    private static final String STK_BROWSER_DEFAULT_URL_SYSPROP = "persist.radio.stk.default_url";

    private static final int NOTIFICATION_ON_KEYGUARD = 1;
    private static final long[] VIBRATION_PATTERN = new long[] { 0, 350, 250, 350 };
    private BroadcastReceiver mUserPresentReceiver = null;

    // The reason based on Intent.ACTION_CLOSE_SYSTEM_DIALOGS.
    private static final String SYSTEM_DIALOG_REASON_KEY = "reason";
    private static final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";
    private static final String SYSTEM_DIALOG_REASON_RECENTAPPS_KEY = "recentapps";
    private BroadcastReceiver mHomeKeyEventReceiver = null;

    @Override
    public void onCreate() {
        CatLog.d(LOG_TAG, "onCreate()+");
        // Initialize members
        int i = 0;
        mContext = getBaseContext();
        mSimCount = TelephonyManager.from(mContext).getActiveModemCount();
        int maxSimCount = TelephonyManager.from(mContext).getSupportedModemCount();
        CatLog.d(LOG_TAG, "simCount: " + mSimCount);
        mStkService = new AppInterface[maxSimCount];
        mStkContext = new StkContext[maxSimCount];

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
                for (i = 0; i < mSimCount; i++) {
                    if (i != slotId
                            && (mStkService[i] != null)
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
                StkAppInstaller.uninstall(this);
                return;
            }
        }

        waitForLooper();

        Message msg = mServiceHandler.obtainMessage(op, 0, slotId);
        switch (op) {
        case OP_CMD:
            msg.obj = args.getParcelable(CMD_MSG);
            break;
        case OP_RESPONSE:
        case OP_CARD_STATUS_CHANGED:
        case OP_LOCALE_CHANGED:
        case OP_ALPHA_NOTIFY:
        case OP_IDLE_SCREEN:
        case OP_STOP_TONE_USER:
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
        unregisterUserActivityReceiver();
        unregisterHomeVisibilityObserver();
        unregisterLocaleChangeReceiver();
        unregisterHomeKeyEventReceiver();
        sInstance = null;
        waitForLooper();
        PhoneConfigurationManager.unregisterForMultiSimConfigChange(mServiceHandler);
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

        PhoneConfigurationManager.registerForMultiSimConfigChange(mServiceHandler,
                EVENT_MULTI_SIM_CONFIG_CHANGED, null);

        Looper.loop();
    }

    /*
     * Package api used by StkMenuActivity to indicate if its on the foreground.
     */
    synchronized void indicateMenuVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mMenuIsVisible = visibility;
        }
    }

    /*
     * Package api used by StkDialogActivity to indicate if its on the foreground.
     */
    synchronized void setDisplayTextDlgVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mDisplayTextDlgIsVisibile = visibility;
        }
    }

    synchronized boolean isInputPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isInputFinishBySrv: " + mStkContext[slotId].mIsInputPending);
            return mStkContext[slotId].mIsInputPending;
        }
        return false;
    }

    synchronized boolean isMenuPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isMenuPending: " + mStkContext[slotId].mIsMenuPending);
            return mStkContext[slotId].mIsMenuPending;
        }
        return false;
    }

    synchronized boolean isDialogPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isDialogPending: " + mStkContext[slotId].mIsDialogPending);
            return mStkContext[slotId].mIsDialogPending;
        }
        return false;
    }

    synchronized boolean isMainMenuAvailable(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            // The main menu can handle the next user operation if the previous session finished.
            return (mStkContext[slotId].lastSelectedItem == null) ? true : false;
        }
        return false;
    }

    /*
     * Package api used by StkMenuActivity to get its Menu parameter.
     */
    synchronized Menu getMenu(int slotId) {
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
    synchronized Menu getMainMenu(int slotId) {
        CatLog.d(LOG_TAG, "StkAppService, getMainMenu, sim id: " + slotId);
        if (slotId >=0 && slotId < mSimCount && (mStkContext[slotId].mMainCmd != null)) {
            Menu menu = mStkContext[slotId].mMainCmd.getMenu();
            if (menu != null) {
                // If alpha identifier or icon identifier with the self-explanatory qualifier is
                // specified in SET-UP MENU command, it should be more prioritized than preset ones.
                if (menu.title == null
                        && (menu.titleIcon == null || !menu.titleIconSelfExplanatory)) {
                    StkMenuConfig config = StkMenuConfig.getInstance(getApplicationContext());
                    String label = config.getLabel(slotId);
                    Bitmap icon = config.getIcon(slotId);
                    if (label != null || icon != null) {
                        Parcel parcel = Parcel.obtain();
                        menu.writeToParcel(parcel, 0);
                        parcel.setDataPosition(0);
                        menu = Menu.CREATOR.createFromParcel(parcel);
                        parcel.recycle();
                        menu.title = label;
                        menu.titleIcon = icon;
                        menu.titleIconSelfExplanatory = false;
                    }
                }
            }
            return menu;
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
            int opcode = msg.what;
            int slotId = msg.arg2;

            CatLog.d(LOG_TAG, "handleMessage opcode[" + opcode + "], sim id[" + slotId + "]");
            if (opcode == OP_CMD && msg.obj != null &&
                    ((CatCmdMessage)msg.obj).getCmdType()!= null) {
                CatLog.d(LOG_TAG, "cmdName[" + ((CatCmdMessage)msg.obj).getCmdType().name() + "]");
            }
            if (slotId >= mStkContext.length || mStkContext[slotId] == null) {
                CatLog.d(LOG_TAG, "invalid slotId " + slotId);
                return;
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
                uninstallIfUnnecessary();
                break;
            case OP_DELAYED_MSG:
                handleDelayedCmd(slotId);
                break;
            case OP_CARD_STATUS_CHANGED:
                CatLog.d(LOG_TAG, "Card/Icc Status change received");
                handleCardStatusChangeAndIccRefresh((Bundle) msg.obj, slotId);
                break;
            case OP_SET_ACT_INST:
                Activity act = (Activity) msg.obj;
                if (mStkContext[slotId].mActivityInstance != act) {
                    CatLog.d(LOG_TAG, "Set pending activity instance - " + act);
                    Activity previous = mStkContext[slotId].mActivityInstance;
                    mStkContext[slotId].mActivityInstance = act;
                    // Finish the previous one if it was replaced with new one
                    // but it has not been finished yet somehow.
                    if (act != null && previous != null && !previous.isDestroyed()
                            && !previous.isFinishing()) {
                        CatLog.d(LOG_TAG, "Finish the previous pending activity - " + previous);
                        previous.finish();
                    }
                }
                // Clear pending dialog instance if it has not been cleared yet.
                Activity dialog = mStkContext[slotId].getPendingDialogInstance();
                if (dialog != null && (dialog.isDestroyed() || dialog.isFinishing())) {
                    CatLog.d(LOG_TAG, "Clear pending dialog instance - " + dialog);
                    mStkContext[slotId].mDialogInstance = null;
                }
                break;
            case OP_SET_DAL_INST:
                Activity dal = (Activity) msg.obj;
                if (mStkContext[slotId].mDialogInstance != dal) {
                    CatLog.d(LOG_TAG, "Set pending dialog instance - " + dal);
                    mStkContext[slotId].mDialogInstance = dal;
                }
                break;
            case OP_SET_IMMED_DAL_INST:
                Activity immedDal = (Activity) msg.obj;
                CatLog.d(LOG_TAG, "Set dialog instance for immediate response. " + immedDal);
                mStkContext[slotId].mImmediateDialogInstance = immedDal;
                break;
            case OP_LOCALE_CHANGED:
                CatLog.d(LOG_TAG, "Locale Changed");
                for (int slot = 0; slot < mSimCount; slot++) {
                    checkForSetupEvent(LANGUAGE_SELECTION_EVENT, (Bundle) msg.obj, slot);
                }
                // rename all registered notification channels on locale change
                createAllChannels();
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
            case OP_STOP_TONE_USER:
            case OP_STOP_TONE:
                CatLog.d(LOG_TAG, "Stop tone");
                handleStopTone(msg, slotId);
                break;
            case OP_USER_ACTIVITY:
                for (int slot = 0; slot < mSimCount; slot++) {
                    checkForSetupEvent(USER_ACTIVITY_EVENT, null, slot);
                }
                break;
            case EVENT_MULTI_SIM_CONFIG_CHANGED:
                handleMultiSimConfigChanged();
                break;
            case OP_HOME_KEY_PRESSED:
                CatLog.d(LOG_TAG, "Process the home key pressed event");
                for (int slot = 0; slot < mSimCount; slot++) {
                    if (mStkContext[slot] != null) {
                        handleHomeKeyPressed(slot);
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
                cancelIdleText(slotId);
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
                mStkService[slotId] = null;
                // Stop the tone currently being played if the relevant SIM is removed or disabled.
                if (mStkContext[slotId].mCurrentCmd != null
                        && mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.PLAY_TONE.value()) {
                    terminateTone(slotId);
                }
                if (!uninstallIfUnnecessary()) {
                    addToMenuSystemOrUpdateLabel();
                }
                if (isAllOtherCardsAbsent(slotId)) {
                    CatLog.d(LOG_TAG, "All CARDs are ABSENT");
                    stopSelf();
                }
            } else {
                IccRefreshResponse state = new IccRefreshResponse();
                state.refreshResult = args.getInt(AppInterface.REFRESH_RESULT);

                CatLog.d(LOG_TAG, "Icc Refresh Result: "+ state.refreshResult);
                if ((state.refreshResult == IccRefreshResponse.REFRESH_RESULT_INIT) ||
                    (state.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET)) {
                    // Clear Idle Text
                    cancelIdleText(slotId);
                }
            }
        }
    }

    private synchronized void handleMultiSimConfigChanged() {
        int oldSimCount = mSimCount;
        mSimCount = TelephonyManager.from(mContext).getActiveModemCount();
        for (int i = oldSimCount; i < mSimCount; i++) {
            CatLog.d(LOG_TAG, "slotId: " + i);
            mStkService[i] = CatService.getInstance(i);
            mStkContext[i] = new StkContext();
            mStkContext[i].mSlotId = i;
            mStkContext[i].mCmdsQ = new LinkedList<DelayedCmd>();
        }

        for (int i = mSimCount; i < oldSimCount; i++) {
            CatLog.d(LOG_TAG, "slotId: " + i);
            if (mStkService[i] != null) {
                mStkService[i].dispose();
                mStkService[i] = null;
            }
            mStkContext[i] = null;
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

    /* package */ boolean isScreenIdle() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }

        String top = tasks.get(0).topActivity.getPackageName();
        if (top == null) {
            return false;
        }

        // We can assume that the screen is idle if the home application is in the foreground.
        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_HOME);

        ResolveInfo info = getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            if (top.equals(info.activityInfo.packageName)) {
                return true;
            }
        }

        return false;
    }

    private synchronized void startToObserveHomeKeyEvent(int slotId) {
        if (mStkContext[slotId].mIsSessionFromUser || mHomeKeyEventReceiver != null) {
            return;
        }
        mHomeKeyEventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                final String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);
                // gesture-based launchers may interpret swipe-up as "recent apps" instead of
                // "home" so we accept both here
                if (SYSTEM_DIALOG_REASON_HOME_KEY.equals(reason)
                    || SYSTEM_DIALOG_REASON_RECENTAPPS_KEY.equals(reason)) {
                    Message message = mServiceHandler.obtainMessage(OP_HOME_KEY_PRESSED);
                    mServiceHandler.sendMessage(message);
                }
            }
        };
        CatLog.d(LOG_TAG, "Started to observe home key event");
        registerReceiver(mHomeKeyEventReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), Context.RECEIVER_EXPORTED);
    }

    private synchronized void unregisterHomeKeyEventReceiver() {
        if (mHomeKeyEventReceiver != null) {
            CatLog.d(LOG_TAG, "Stopped to observe home key event");
            unregisterReceiver(mHomeKeyEventReceiver);
            mHomeKeyEventReceiver = null;
        }
        if (mServiceHandler != null) {
            mServiceHandler.removeMessages(OP_HOME_KEY_PRESSED);
        }
    }

    private void handleHomeKeyPressed(int slotId) {
        // It might be hard for user to recognize that the dialog or screens belong to SIM Toolkit
        // application if the current session was not initiated by user but by the SIM card,
        // so it is recommended to send TERMINAL RESPONSE if user press the home key.
        if (!mStkContext[slotId].mIsSessionFromUser) {
            Activity dialog = mStkContext[slotId].getPendingDialogInstance();
            Activity activity = mStkContext[slotId].getPendingActivityInstance();
            if (dialog != null) {
                dialog.finish();
                mStkContext[slotId].mDialogInstance = null;
            } else if (activity != null) {
                activity.finish();
                mStkContext[slotId].mActivityInstance = null;
            }
        }
    }

    private void handleIdleScreen(int slotId) {
        // If the idle screen event is present in the list need to send the
        // response to SIM.
        CatLog.d(LOG_TAG, "Need to send IDLE SCREEN Available event to SIM");
        checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null, slotId);

        if (mStkContext[slotId].mIdleModeTextCmd != null
                && !mStkContext[slotId].mIdleModeTextVisible) {
            launchIdleText(slotId);
        }
    }

    private void sendScreenBusyResponse(int slotId) {
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }
        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);
        CatLog.d(LOG_TAG, "SCREEN_BUSY");
        resMsg.setResultCode(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        mStkService[slotId].onCmdResponse(resMsg);
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            callDelayedMsg(slotId);
        } else {
            mStkContext[slotId].mCmdInProgress = false;
        }
    }

    /**
     * Sends TERMINAL RESPONSE or ENVELOPE
     *
     * @param args detailed parameters of the response
     * @param slotId slot identifier
     */
    public void sendResponse(Bundle args, int slotId) {
        Message msg = mServiceHandler.obtainMessage(OP_RESPONSE, 0, slotId, args);
        mServiceHandler.sendMessage(msg);
    }

    private void sendResponse(int resId, int slotId, boolean confirm) {
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        args.putBoolean(StkAppService.CONFIRMATION, confirm);
        sendResponse(args, slotId);
    }

    private void terminateTone(int slotId) {
        Message msg = new Message();
        msg.what = OP_STOP_TONE;
        msg.obj = mServiceHandler.hasMessages(OP_STOP_TONE, PLAY_TONE_WITH_DIALOG)
                ? PLAY_TONE_WITH_DIALOG : PLAY_TONE_ONLY;
        handleStopTone(msg, slotId);
    }

    private boolean isCmdInteractive(CatCmdMessage cmd) {
        switch (cmd.getCmdType()) {
        case SEND_DTMF:
        case SEND_SMS:
        case REFRESH:
        case RUN_AT:
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
        Message msg = mServiceHandler.obtainMessage(OP_DELAYED_MSG, 0, slotId);
        mServiceHandler.sendMessage(msg);
    }

    private void callSetActivityInstMsg(int opcode, int slotId, Object obj) {
        Message msg = mServiceHandler.obtainMessage(opcode, 0, slotId, obj);
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
        mStkContext[slotId].mNoResponseFromUser = false;

        if (mStkContext[slotId].mMainCmd == null) {
            CatLog.d(LOG_TAG, "[handleSessionEnd][mMainCmd is null!]");
        }
        mStkContext[slotId].lastSelectedItem = null;
        mStkContext[slotId].mIsSessionFromUser = false;
        // In case of SET UP MENU command which removed the app, don't
        // update the current menu member.
        if (mStkContext[slotId].mCurrentMenu != null && mStkContext[slotId].mMainCmd != null) {
            mStkContext[slotId].mCurrentMenu = mStkContext[slotId].mMainCmd.getMenu();
        }
        CatLog.d(LOG_TAG, "[handleSessionEnd][mMenuState]" + mStkContext[slotId].mMenuIsVisible);

        if (StkMenuActivity.STATE_SECONDARY == mStkContext[slotId].mMenuState) {
            mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
        }

        // Send a local broadcast as a notice that this service handled the session end event.
        Intent intent = new Intent(SESSION_ENDED);
        intent.putExtra(SLOT_ID, slotId);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

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
    boolean isTopOfStack() {
        ActivityManager mActivityManager = (ActivityManager) mContext
                .getSystemService(ACTIVITY_SERVICE);
        String currentPackageName = null;
        List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks == null || tasks.get(0).topActivity == null) {
            return false;
        }
        currentPackageName = tasks.get(0).topActivity.getPackageName();
        if (null != currentPackageName) {
            return currentPackageName.equals(PACKAGE_NAME);
        }
        return false;
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param context the context to get carrier service
     * @param key config key defined in CarrierConfigManager
     * @param slotId slot ID.
     * @return boolean value of corresponding key.
     */
    /* package */ static boolean getBooleanCarrierConfig(Context context, String key, int slotId) {
        CarrierConfigManager ccm = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        PersistableBundle b = null;
        if (ccm != null && sm != null) {
            SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(slotId);
            if (info != null) {
                b = ccm.getConfigForSubId(info.getSubscriptionId());
            }
        }
        if (b != null) {
            return b.getBoolean(key);
        }
        // Return static default defined in CarrierConfigManager.
        return CarrierConfigManager.getDefaultConfig().getBoolean(key);
    }

    private boolean getBooleanCarrierConfig(String key, int slotId) {
        return getBooleanCarrierConfig(this, key, slotId);
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
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
                //Check other setup menu state. If all setup menu are removed, uninstall apk.
                if (!uninstallIfUnnecessary()) {
                    addToMenuSystemOrUpdateLabel();
                }
            } else {
                addToMenuSystemOrUpdateLabel();
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
            if (idleModeText == null || TextUtils.isEmpty(idleModeText.text)) {
                cancelIdleText(slotId);
            }
            mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
            if (mStkContext[slotId].mIdleModeTextCmd != null) {
                if (mStkContext[slotId].mIdleModeTextVisible || isScreenIdle()) {
                    CatLog.d(LOG_TAG, "set up idle mode");
                    launchIdleText(slotId);
                } else {
                    registerHomeVisibilityObserver();
                }
            }
            break;
        case SEND_DTMF:
        case SEND_SMS:
        case REFRESH:
        case RUN_AT:
        case SEND_SS:
        case SEND_USSD:
        case GET_CHANNEL_STATUS:
            waitForUsersResponse = false;
            launchEventMessage(slotId);
            break;
        case LAUNCH_BROWSER:
            // The device setup process should not be interrupted by launching browser.
            if (Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
                CatLog.d(LOG_TAG, "Not perform if the setup process has not been completed.");
                sendScreenBusyResponse(slotId);
                break;
            }

            /* Check if Carrier would not want to launch browser */
            if (getBooleanCarrierConfig(CarrierConfigManager.KEY_STK_DISABLE_LAUNCH_BROWSER_BOOL,
                    slotId)) {
                CatLog.d(LOG_TAG, "Browser is not launched as per carrier.");
                sendResponse(RES_ID_DONE, slotId, true);
                break;
            }

            mStkContext[slotId].mBrowserSettings =
                    mStkContext[slotId].mCurrentCmd.getBrowserSettings();
            if (!isUrlAvailableToLaunchBrowser(mStkContext[slotId].mBrowserSettings)) {
                CatLog.d(LOG_TAG, "Browser url property is not set - send error");
                sendResponse(RES_ID_ERROR, slotId, true);
            } else {
                TextMessage alphaId = mStkContext[slotId].mCurrentCmd.geTextMessage();
                if ((alphaId == null) || TextUtils.isEmpty(alphaId.text)) {
                    // don't need user confirmation in this case
                    // just launch the browser or spawn a new tab
                    CatLog.d(LOG_TAG, "user confirmation is not currently needed.\n" +
                            "supressing confirmation dialogue and confirming silently...");
                    mStkContext[slotId].launchBrowser = true;
                    sendResponse(RES_ID_CONFIRM, slotId, true);
                } else {
                    launchConfirmationDialog(alphaId, slotId);
                }
            }
            break;
        case SET_UP_CALL:
            TextMessage mesg = mStkContext[slotId].mCurrentCmd.getCallSettings().confirmMsg;
            if((mesg != null) && (mesg.text == null || mesg.text.length() == 0)) {
                mesg.text = getResources().getString(R.string.default_setup_call_msg);
            }
            CatLog.d(LOG_TAG, "SET_UP_CALL mesg.text " + mesg.text);
            launchConfirmationDialog(mesg, slotId);
            break;
        case PLAY_TONE:
            handlePlayTone(slotId);
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
            replaceEventList(slotId);
            if (isScreenIdle()) {
                CatLog.d(LOG_TAG," Check if IDLE_SCREEN_AVAILABLE_EVENT is present in List");
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

    private void addToMenuSystemOrUpdateLabel() {
        String candidateLabel = null;

        for (int slotId = 0; slotId < mSimCount; slotId++) {
            Menu menu = getMainMenu(slotId);
            if (menu != null) {
                if (!TextUtils.isEmpty(candidateLabel)) {
                    if (!TextUtils.equals(menu.title, candidateLabel)) {
                        // We should not display the alpha identifier of SET-UP MENU command
                        // as the application label on the application launcher
                        // if different alpha identifiers are provided by multiple SIMs.
                        candidateLabel = null;
                        break;
                    }
                } else {
                    if (TextUtils.isEmpty(menu.title)) {
                        break;
                    }
                    candidateLabel = menu.title;
                }
            }
        }

        StkAppInstaller.installOrUpdate(this, candidateLabel);
    }

    @SuppressWarnings("FallThrough")
    private void handleCmdResponse(Bundle args, int slotId) {
        CatLog.d(LOG_TAG, "handleCmdResponse, sim id: " + slotId);
        unregisterHomeKeyEventReceiver();
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }

        if (mStkService[slotId] == null) {
            mStkService[slotId] = CatService.getInstance(slotId);
            if (mStkService[slotId] == null) {
                // CatService is disposed when the relevant SIM is removed or disabled.
                // StkAppService can also be stopped when the absent state is notified,
                // so this situation can happen.
                CatLog.d(LOG_TAG, "No response is sent back to the missing CatService.");
                return;
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
                mStkContext[slotId].mIsSessionFromUser = true;
                // Fall through
            case SELECT_ITEM:
                mStkContext[slotId].lastSelectedItem = getItemName(menuSelection, slotId);
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
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
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                    resMsg.setInput(input);
                }
            }
            break;
        case RES_ID_CONFIRM:
            CatLog.d(LOG_TAG, "RES_ID_CONFIRM");
            confirmed = args.getBoolean(CONFIRMATION);
            switch (mStkContext[slotId].mCurrentCmd.getCmdType()) {
            case DISPLAY_TEXT:
                if (confirmed) {
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                } else {
                    resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
                }
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
            CatLog.d(LOG_TAG, "User Choice=" + choice);
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
        case RES_ID_ERROR:
            CatLog.d(LOG_TAG, "RES_ID_ERROR");
            switch (mStkContext[slotId].mCurrentCmd.getCmdType()) {
            case LAUNCH_BROWSER:
                resMsg.setResultCode(ResultCode.LAUNCH_BROWSER_ERROR);
                break;
            }
            break;
        default:
            CatLog.d(LOG_TAG, "Unknown result id");
            return;
        }

        switch (args.getInt(RES_ID)) {
            case RES_ID_MENU_SELECTION:
            case RES_ID_INPUT:
            case RES_ID_CONFIRM:
            case RES_ID_CHOICE:
            case RES_ID_BACKWARD:
            case RES_ID_END_SESSION:
                mStkContext[slotId].mNoResponseFromUser = false;
                break;
            case RES_ID_TIMEOUT:
                cancelNotificationOnKeyguard(slotId);
                mStkContext[slotId].mNoResponseFromUser = true;
                break;
            default:
                // The other IDs cannot be used to judge if there is no response from user.
                break;
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
     * No terminal response will be sent for pending instances.
     */
    private void cleanUpInstanceStackBySlot(int slotId) {
        Activity activity = mStkContext[slotId].getPendingActivityInstance();
        Activity dialog = mStkContext[slotId].getPendingDialogInstance();
        CatLog.d(LOG_TAG, "cleanUpInstanceStackBySlot slotId: " + slotId);
        if (activity != null) {
            if (mStkContext[slotId].mCurrentCmd != null) {
                CatLog.d(LOG_TAG, "current cmd type: " +
                        mStkContext[slotId].mCurrentCmd.getCmdType());
                if (mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.GET_INPUT.value()
                        || mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.GET_INKEY.value()) {
                    mStkContext[slotId].mIsInputPending = true;
                } else if (mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.SET_UP_MENU.value()
                        || mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.SELECT_ITEM.value()) {
                    mStkContext[slotId].mIsMenuPending = true;
                }
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

    @Override
    public void startActivity(Intent intent) {
        int slotId = intent.getIntExtra(SLOT_ID, SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        // Close the dialog displayed for DISPLAY TEXT command with an immediate response object
        // before new dialog is displayed.
        if (SubscriptionManager.isValidSlotIndex(slotId)) {
            Activity dialog = mStkContext[slotId].getImmediateDialogInstance();
            if (dialog != null) {
                CatLog.d(LOG_TAG, "finish dialog for immediate response.");
                dialog.finish();
            }
        }
        super.startActivity(intent);
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
        if (mStkContext[slotId].mMenuState == StkMenuActivity.STATE_SECONDARY) {
            startToObserveHomeKeyEvent(slotId);
        }
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        newIntent.setFlags(intentFlags);
        startActivity(newIntent);
    }

    private void launchInputActivity(int slotId) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        String targetActivity = STK_INPUT_ACTIVITY_NAME;
        String uriString = STK_INPUT_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);
        Input input = mStkContext[slotId].mCurrentCmd.geInput();

        CatLog.d(LOG_TAG, "launchInputActivity, slotId: " + slotId);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        newIntent.putExtra("INPUT", input);
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);

        if (input != null) {
            notifyUserIfNecessary(slotId, input.text);
        }
        startActivity(newIntent);
        startToObserveHomeKeyEvent(slotId);
    }

    private void launchTextDialog(int slotId) {
        CatLog.d(LOG_TAG, "launchTextDialog, slotId: " + slotId);
        Intent newIntent = new Intent();
        String targetActivity = STK_DIALOG_ACTIVITY_NAME;
        int action = getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId);
        String uriString = STK_DIALOG_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);
        TextMessage textMessage = mStkContext[slotId].mCurrentCmd.geTextMessage();

        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.setData(uriData);
        newIntent.putExtra("TEXT", textMessage);
        newIntent.putExtra(SLOT_ID, slotId);

        if (textMessage != null) {
            notifyUserIfNecessary(slotId, textMessage.text);
        }
        startActivity(newIntent);
        // For display texts with immediate response, send the terminal response
        // immediately. responseNeeded will be false, if display text command has
        // the immediate response tlv.
        if (!mStkContext[slotId].mCurrentCmd.geTextMessage().responseNeeded) {
            sendResponse(RES_ID_CONFIRM, slotId, true);
        } else {
            startToObserveHomeKeyEvent(slotId);
        }
    }

    private void notifyUserIfNecessary(int slotId, String message) {
        createAllChannels();

        if (mStkContext[slotId].mNoResponseFromUser) {
            // No response from user was observed in the current session.
            // Do nothing in that case in order to avoid turning on the screen again and again
            // when the card repeatedly sends the same command in its retry procedure.
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        if (((KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()) {
            // Display the notification on the keyguard screen
            // if user cannot see the message from the card right now because of it.
            // The notification can be dismissed if user removed the keyguard screen.
            launchNotificationOnKeyguard(slotId, message);
        }

        // Turn on the screen.
        PowerManager.WakeLock wakelock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        wakelock.acquire();
        wakelock.release();
    }

    private void launchNotificationOnKeyguard(int slotId, String message) {
        Notification.Builder builder = new Notification.Builder(this, STK_NOTIFICATION_CHANNEL_ID);
        setNotificationTitle(slotId, builder);

        builder.setStyle(new Notification.BigTextStyle(builder).bigText(message));
        builder.setContentText(message);
        builder.setSmallIcon(R.drawable.stat_notify_sim_toolkit);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setColor(getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color));

        registerUserPresentReceiver();
        mNotificationManager.notify(getNotificationId(NOTIFICATION_ON_KEYGUARD, slotId),
                builder.build());
        mStkContext[slotId].mNotificationOnKeyguard = true;
    }

    private void cancelNotificationOnKeyguard(int slotId) {
        mNotificationManager.cancel(getNotificationId(NOTIFICATION_ON_KEYGUARD, slotId));
        mStkContext[slotId].mNotificationOnKeyguard = false;
        unregisterUserPresentReceiver(slotId);
    }

    private synchronized void registerUserPresentReceiver() {
        if (mUserPresentReceiver == null) {
            mUserPresentReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        for (int slot = 0; slot < mSimCount; slot++) {
                            cancelNotificationOnKeyguard(slot);
                        }
                    }
                }
            };
            registerReceiver(mUserPresentReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        }
    }

    private synchronized void unregisterUserPresentReceiver(int slotId) {
        if (mUserPresentReceiver != null) {
            for (int slot = 0; slot < mSimCount; slot++) {
                if (slot != slotId) {
                    if (mStkContext[slot].mNotificationOnKeyguard) {
                        // The broadcast receiver is still necessary for other SIM card.
                        return;
                    }
                }
            }
            unregisterReceiver(mUserPresentReceiver);
            mUserPresentReceiver = null;
        }
    }

    private int getNotificationId(int notificationType, int slotId) {
        return getNotificationId(slotId) + (notificationType * mSimCount);
    }

    private void replaceEventList(int slotId) {
        if (mStkContext[slotId].mSetupEventListSettings != null) {
            for (int current : mStkContext[slotId].mSetupEventListSettings.eventList) {
                if (current != INVALID_SETUP_EVENT) {
                    // Cancel the event notification if it is not listed in the new event list.
                    if ((mStkContext[slotId].mCurrentCmd.getSetEventList() == null)
                            || !findEvent(current, mStkContext[slotId].mCurrentCmd
                            .getSetEventList().eventList)) {
                        unregisterEvent(current, slotId);
                    }
                }
            }
        }
        mStkContext[slotId].mSetupEventListSettings
                = mStkContext[slotId].mCurrentCmd.getSetEventList();
        mStkContext[slotId].mCurrentSetupEventCmd = mStkContext[slotId].mCurrentCmd;
        mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
        registerEvents(slotId);
    }

    private boolean findEvent(int event, int[] eventList) {
        for (int content : eventList) {
            if (content == event) return true;
        }
        return false;
    }

    private void unregisterEvent(int event, int slotId) {
        for (int slot = 0; slot < mSimCount; slot++) {
            if (slot != slotId) {
                if (mStkContext[slot].mSetupEventListSettings != null) {
                    if (findEvent(event, mStkContext[slot].mSetupEventListSettings.eventList)) {
                        // The specified event shall never be canceled
                        // if there is any other SIM card which requests the event.
                        return;
                    }
                }
            }
        }

        switch (event) {
            case USER_ACTIVITY_EVENT:
                unregisterUserActivityReceiver();
                break;
            case IDLE_SCREEN_AVAILABLE_EVENT:
                unregisterHomeVisibilityObserver(AppInterface.CommandType.SET_UP_EVENT_LIST, slotId);
                break;
            case LANGUAGE_SELECTION_EVENT:
                unregisterLocaleChangeReceiver();
                break;
            default:
                break;
        }
    }

    private void registerEvents(int slotId) {
        if (mStkContext[slotId].mSetupEventListSettings == null) {
            return;
        }
        for (int event : mStkContext[slotId].mSetupEventListSettings.eventList) {
            switch (event) {
                case USER_ACTIVITY_EVENT:
                    registerUserActivityReceiver();
                    break;
                case IDLE_SCREEN_AVAILABLE_EVENT:
                    registerHomeVisibilityObserver();
                    break;
                case LANGUAGE_SELECTION_EVENT:
                    registerLocaleChangeReceiver();
                    break;
                default:
                    break;
            }
        }
    }

    private synchronized void registerUserActivityReceiver() {
        if (mUserActivityReceiver == null) {
            mUserActivityReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (TelephonyIntents.ACTION_USER_ACTIVITY_NOTIFICATION.equals(
                            intent.getAction())) {
                        Message message = mServiceHandler.obtainMessage(OP_USER_ACTIVITY);
                        mServiceHandler.sendMessage(message);
                        unregisterUserActivityReceiver();
                    }
                }
            };
            registerReceiver(mUserActivityReceiver, new IntentFilter(
                    TelephonyIntents.ACTION_USER_ACTIVITY_NOTIFICATION));
            try {
                ITelephony telephony = ITelephony.Stub.asInterface(
                        TelephonyFrameworkInitializer
                                .getTelephonyServiceManager()
                                .getTelephonyServiceRegisterer()
                                .get());
                telephony.requestUserActivityNotification();
            } catch (RemoteException e) {
                CatLog.e(LOG_TAG, "failed to init WindowManager:" + e);
            }
        }
    }

    private synchronized void unregisterUserActivityReceiver() {
        if (mUserActivityReceiver != null) {
            unregisterReceiver(mUserActivityReceiver);
            mUserActivityReceiver = null;
        }
    }

    private synchronized void registerHomeVisibilityObserver() {
        if (mHomeVisibilityListener == null) {
            mHomeVisibilityListener = new HomeVisibilityListener() {
                @Override
                public void onHomeVisibilityChanged(boolean isHomeActivityVisible) {
                    if (isHomeActivityVisible) {
                        Message message = mServiceHandler.obtainMessage(OP_IDLE_SCREEN);
                        mServiceHandler.sendMessage(message);
                        unregisterHomeVisibilityObserver();
                    }
                }
            };
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.addHomeVisibilityListener(Runnable::run, mHomeVisibilityListener);
            CatLog.d(LOG_TAG, "Started to observe the foreground activity");
        }
    }

    private void unregisterHomeVisibilityObserver(AppInterface.CommandType command, int slotId) {
        // Check if there is any pending command which still needs the process observer
        // except for the current command and slot.
        for (int slot = 0; slot < mSimCount; slot++) {
            if (command != AppInterface.CommandType.SET_UP_IDLE_MODE_TEXT || slot != slotId) {
                if (mStkContext[slot].mIdleModeTextCmd != null
                        && !mStkContext[slot].mIdleModeTextVisible) {
                    // Keep the process observer registered
                    // as there is an idle mode text which has not been visible yet.
                    return;
                }
            }
            if (command != AppInterface.CommandType.SET_UP_EVENT_LIST || slot != slotId) {
                if (mStkContext[slot].mSetupEventListSettings != null) {
                    if (findEvent(IDLE_SCREEN_AVAILABLE_EVENT,
                                mStkContext[slot].mSetupEventListSettings.eventList)) {
                        // Keep the process observer registered
                        // as there is a SIM card which still want IDLE SCREEN AVAILABLE event.
                        return;
                    }
                }
            }
        }
        unregisterHomeVisibilityObserver();
    }

    private synchronized void unregisterHomeVisibilityObserver() {
        if (mHomeVisibilityListener != null) {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.removeHomeVisibilityListener(mHomeVisibilityListener);
            CatLog.d(LOG_TAG, "Stopped to observe the foreground activity");
            mHomeVisibilityListener = null;
        }
    }

    private synchronized void registerLocaleChangeReceiver() {
        if (mLocaleChangeReceiver == null) {
            mLocaleChangeReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                        Message message = mServiceHandler.obtainMessage(OP_LOCALE_CHANGED);
                        mServiceHandler.sendMessage(message);
                    }
                }
            };
            registerReceiver(mLocaleChangeReceiver, new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
        }
    }

    private synchronized void unregisterLocaleChangeReceiver() {
        if (mLocaleChangeReceiver != null) {
            unregisterReceiver(mLocaleChangeReceiver);
            mLocaleChangeReceiver = null;
        }
    }

    private void sendSetUpEventResponse(int event, byte[] addedInfo, int slotId) {
        CatLog.d(LOG_TAG, "sendSetUpEventResponse: event : " + event + "slotId = " + slotId);

        if (mStkContext[slotId].mCurrentSetupEventCmd == null){
            CatLog.e(LOG_TAG, "mCurrentSetupEventCmd is null");
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
        CatLog.d(LOG_TAG, "Event :" + event);

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
                CatLog.d(LOG_TAG, " Event " + event + "exists in the EventList");

                switch (event) {
                    case USER_ACTIVITY_EVENT:
                    case IDLE_SCREEN_AVAILABLE_EVENT:
                        sendSetUpEventResponse(event, addedInfo, slotId);
                        removeSetUpEvent(event, slotId);
                        break;
                    case LANGUAGE_SELECTION_EVENT:
                        String language =  mContext
                                .getResources().getConfiguration().locale.getLanguage();
                        CatLog.d(LOG_TAG, "language: " + language);
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
                CatLog.e(LOG_TAG, " Event does not exist in the EventList");
            }
        } else {
            CatLog.e(LOG_TAG, "SetupEventList is not received. Ignoring the event: " + event);
        }
    }

    private void removeSetUpEvent(int event, int slotId) {
        CatLog.d(LOG_TAG, "Remove Event :" + event);

        if (mStkContext[slotId].mSetupEventListSettings != null) {
            /*
             * Make new  Eventlist without the event
             */
            for (int i = 0; i < mStkContext[slotId].mSetupEventListSettings.eventList.length; i++) {
                if (event == mStkContext[slotId].mSetupEventListSettings.eventList[i]) {
                    mStkContext[slotId].mSetupEventListSettings.eventList[i] = INVALID_SETUP_EVENT;

                    switch (event) {
                        case USER_ACTIVITY_EVENT:
                            // The broadcast receiver can be unregistered
                            // as the event has already been sent to the card.
                            unregisterUserActivityReceiver();
                            break;
                        case IDLE_SCREEN_AVAILABLE_EVENT:
                            // The process observer can be unregistered
                            // as the idle screen has already been available.
                            unregisterHomeVisibilityObserver();
                            break;
                        default:
                            break;
                    }
                    break;
                }
            }
        }
    }

    private void launchEventMessage(int slotId) {
        launchEventMessage(slotId, mStkContext[slotId].mCurrentCmd.geTextMessage());
    }

    private void launchEventMessage(int slotId, TextMessage msg) {
        if (msg == null || msg.text == null || (msg.text != null && msg.text.length() == 0)) {
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
        /* In case of 'self explanatory' stkapp should display the specified
         * icon in proactive command (but not the alpha string).
         * If icon is non-self explanatory and if the icon could not be displayed
         * then alpha string or text data should be displayed
         * Ref: ETSI 102.223,section 6.5.4
         */
        if (mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ||
                msg.icon == null || !msg.iconSelfExplanatory) {
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

    private void launchBrowser(BrowserSettings settings) {
        if (settings == null) {
            return;
        }

        Uri data = null;
        String url;
        if (settings.url == null) {
            // if the command did not contain a URL,
            // launch the browser to the default homepage.
            CatLog.d(LOG_TAG, "no url data provided by proactive command." +
                       " launching browser with stk default URL ... ");
            url = SystemProperties.get(STK_BROWSER_DEFAULT_URL_SYSPROP,
                    "http://www.google.com");
        } else {
            CatLog.d(LOG_TAG, "launch browser command has attached url = " + settings.url);
            url = settings.url;
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            data = Uri.parse(url);
            CatLog.d(LOG_TAG, "launching browser with url = " + url);
        } else {
            String modifiedUrl = "http://" + url;
            data = Uri.parse(modifiedUrl);
            CatLog.d(LOG_TAG, "launching browser with modified url = " + modifiedUrl);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(data);
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
            Thread.sleep(3000);
        } catch (InterruptedException e) {}
    }

    private void cancelIdleText(int slotId) {
        unregisterHomeVisibilityObserver(AppInterface.CommandType.SET_UP_IDLE_MODE_TEXT, slotId);
        mNotificationManager.cancel(getNotificationId(slotId));
        mStkContext[slotId].mIdleModeTextCmd = null;
        mStkContext[slotId].mIdleModeTextVisible = false;
    }

    private void launchIdleText(int slotId) {
        TextMessage msg = mStkContext[slotId].mIdleModeTextCmd.geTextMessage();

        if (msg != null && !TextUtils.isEmpty(msg.text)) {
            CatLog.d(LOG_TAG, "launchIdleText - text[" + msg.text
                    + "] iconSelfExplanatory[" + msg.iconSelfExplanatory
                    + "] icon[" + msg.icon + "], sim id: " + slotId);
            CatLog.d(LOG_TAG, "Add IdleMode text");
            PendingIntent pendingIntent = PendingIntent.getService(mContext, 0,
                    new Intent(mContext, StkAppService.class), PendingIntent.FLAG_IMMUTABLE);
            createAllChannels();
            final Notification.Builder notificationBuilder = new Notification.Builder(
                    StkAppService.this, STK_NOTIFICATION_CHANNEL_ID);
            setNotificationTitle(slotId, notificationBuilder);
            notificationBuilder
                    .setSmallIcon(R.drawable.stat_notify_sim_toolkit);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setOngoing(true);
            notificationBuilder.setOnlyAlertOnce(true);
            // Set text and icon for the status bar and notification body.
            if (mStkContext[slotId].mIdleModeTextCmd.hasIconLoadFailed() ||
                    !msg.iconSelfExplanatory) {
                notificationBuilder.setContentText(msg.text);
                notificationBuilder.setTicker(msg.text);
                notificationBuilder.setStyle(new Notification.BigTextStyle(notificationBuilder)
                        .bigText(msg.text));
            }
            if (msg.icon != null) {
                notificationBuilder.setLargeIcon(msg.icon);
            } else {
                Bitmap bitmapIcon = BitmapFactory.decodeResource(StkAppService.this
                    .getResources().getSystem(),
                    R.drawable.stat_notify_sim_toolkit);
                notificationBuilder.setLargeIcon(bitmapIcon);
            }
            notificationBuilder.setColor(mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color));
            mNotificationManager.notify(getNotificationId(slotId), notificationBuilder.build());
            mStkContext[slotId].mIdleModeTextVisible = true;
        }
    }

    private void setNotificationTitle(int slotId, Notification.Builder builder) {
        Menu menu = getMainMenu(slotId);
        if (menu == null || TextUtils.isEmpty(menu.title)
                || TextUtils.equals(menu.title, getResources().getString(R.string.app_name))) {
            // No need to set a content title in the content area if no title (alpha identifier
            // of SET-UP MENU command) is available for the specified slot or the title is same
            // as the application label.
            return;
        }

        for (int index = 0; index < mSimCount; index++) {
            if (index != slotId) {
                Menu otherMenu = getMainMenu(index);
                if (otherMenu != null && !TextUtils.equals(menu.title, otherMenu.title)) {
                    // Set the title (alpha identifier of SET-UP MENU command) as the content title
                    // to differentiate it from other main menu with different alpha identifier
                    // (including null) is available.
                    builder.setContentTitle(menu.title);
                    return;
                }
            }
        }
    }

    /** Creates the notification channel and registers it with NotificationManager.
     * If a channel with the same ID is already registered, NotificationManager will
     * ignore this call.
     */
    private void createAllChannels() {
        NotificationChannel notificationChannel = new NotificationChannel(
                STK_NOTIFICATION_CHANNEL_ID,
                getResources().getString(R.string.stk_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);

        notificationChannel.enableVibration(true);
        notificationChannel.setVibrationPattern(VIBRATION_PATTERN);

        mNotificationManager.createNotificationChannel(notificationChannel);
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

    private void handlePlayTone(int slotId) {
        TextMessage toneMsg = mStkContext[slotId].mCurrentCmd.geTextMessage();

        boolean showUser = true;
        boolean displayDialog = true;
        Resources resource = Resources.getSystem();
        try {
            displayDialog = !resource.getBoolean(
                    R.bool.config_stkNoAlphaUsrCnf);
        } catch (NotFoundException e) {
            displayDialog = true;
        }

        // As per the spec 3GPP TS 11.14, 6.4.5. Play Tone.
        // If there is no alpha identifier tlv present, UE may show the
        // user information. 'config_stkNoAlphaUsrCnf' value will decide
        // whether to show it or not.
        // If alpha identifier tlv is present and its data is null, play only tone
        // without showing user any information.
        // Alpha Id is Present, but the text data is null.
        if ((toneMsg.text != null ) && (toneMsg.text.equals(""))) {
            CatLog.d(LOG_TAG, "Alpha identifier data is null, play only tone");
            showUser = false;
        }
        // Alpha Id is not present AND we need to show info to the user.
        if (toneMsg.text == null && displayDialog) {
            CatLog.d(LOG_TAG, "toneMsg.text " + toneMsg.text
                    + " Starting ToneDialog activity with default message.");
            toneMsg.text = getResources().getString(R.string.default_tone_dialog_msg);
            showUser = true;
        }
        // Dont show user info, if config setting is true.
        if (toneMsg.text == null && !displayDialog) {
            CatLog.d(LOG_TAG, "config value stkNoAlphaUsrCnf is true");
            showUser = false;
        }

        CatLog.d(LOG_TAG, "toneMsg.text: " + toneMsg.text + "showUser: " +showUser +
                "displayDialog: " +displayDialog);
        playTone(showUser, slotId);
    }

    private void playTone(boolean showUserInfo, int slotId) {
        // Start playing tone and vibration
        ToneSettings settings = mStkContext[slotId].mCurrentCmd.getToneSettings();
        if (null == settings) {
            CatLog.d(LOG_TAG, "null settings, not playing tone.");
            return;
        }

        mVibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        mTonePlayer = new TonePlayer();
        mTonePlayer.play(settings.tone);
        int timeout = StkApp.calculateDurationInMilis(settings.duration);
        if (timeout == 0) {
            timeout = StkApp.TONE_DEFAULT_TIMEOUT;
        }

        Message msg = mServiceHandler.obtainMessage(OP_STOP_TONE, 0, slotId,
                (showUserInfo ? PLAY_TONE_WITH_DIALOG : PLAY_TONE_ONLY));
        mServiceHandler.sendMessageDelayed(msg, timeout);
        if (settings.vibrate) {
            mVibrator.vibrate(timeout);
        }

        // Start Tone dialog Activity to show user the information.
        if (showUserInfo) {
            Intent newIntent = new Intent(sInstance, ToneDialog.class);
            String uriString = STK_TONE_URI + slotId;
            Uri uriData = Uri.parse(uriString);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
            newIntent.putExtra("TEXT", mStkContext[slotId].mCurrentCmd.geTextMessage());
            newIntent.putExtra(SLOT_ID, slotId);
            newIntent.setData(uriData);
            startActivity(newIntent);
        }
    }

    private void finishToneDialogActivity() {
        Intent finishIntent = new Intent(FINISH_TONE_ACTIVITY_ACTION);
        sendBroadcast(finishIntent);
    }

    private void handleStopTone(Message msg, int slotId) {
        int resId = 0;

        // Stop the play tone in following cases:
        // 1.OP_STOP_TONE: play tone timer expires.
        // 2.STOP_TONE_USER: user pressed the back key.
        if (msg.what == OP_STOP_TONE) {
            resId = RES_ID_DONE;
            // Dismiss Tone dialog, after finishing off playing the tone.
            if (PLAY_TONE_WITH_DIALOG.equals((Integer) msg.obj)) finishToneDialogActivity();
        } else if (msg.what == OP_STOP_TONE_USER) {
            resId = RES_ID_END_SESSION;
        }

        sendResponse(resId, slotId, true);

        mServiceHandler.removeMessages(OP_STOP_TONE);
        mServiceHandler.removeMessages(OP_STOP_TONE_USER);

        if (mTonePlayer != null) {
            mTonePlayer.stop();
            mTonePlayer.release();
            mTonePlayer = null;
        }
        if (mVibrator != null) {
            mVibrator.cancel();
            mVibrator = null;
        }
    }

    boolean isNoTonePlaying() {
        return mTonePlayer == null ? true : false;
    }

    private void launchOpenChannelDialog(final int slotId) {
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
                            sendResponse(args, slotId);
                        }
                    })
                    .setNegativeButton(getResources().getString(R.string.stk_dialog_reject),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, NO);
                            sendResponse(args, slotId);
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                R.bool.config_sf_slowBlur)) {
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
        if (!mContext.getResources().getBoolean(R.bool.config_sf_slowBlur)) {
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
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(LOG_TAG, "Unable to get Menu's items size");
            return true;
        }
        return false;
    }

    private boolean uninstallIfUnnecessary() {
        for (int slot = 0; slot < mSimCount; slot++) {
            if (mStkContext[slot].mMainCmd != null) {
                return false;
            }
        }
        CatLog.d(LOG_TAG, "Uninstall App");
        StkAppInstaller.uninstall(this);
        return true;
    }

    synchronized StkContext getStkContext(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            return mStkContext[slotId];
        } else {
            CatLog.d(LOG_TAG, "invalid slotId: " + slotId);
            return null;
        }
    }

    private void handleAlphaNotify(Bundle args) {
        String alphaString = args.getString(AppInterface.ALPHA_STRING);

        CatLog.d(LOG_TAG, "Alpha string received from card: " + alphaString);
        Toast toast = Toast.makeText(sInstance, alphaString, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
    }

    private boolean isUrlAvailableToLaunchBrowser(BrowserSettings settings) {
        String url = SystemProperties.get(STK_BROWSER_DEFAULT_URL_SYSPROP, "");
        if (url == "" && settings.url == null) {
            return false;
        }
        return true;
    }
}
