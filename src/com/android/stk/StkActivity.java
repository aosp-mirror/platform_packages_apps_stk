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

import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.android.internal.telephony.gsm.stk.*;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.BitSet;
import java.util.List;

/**
 * Display main menu and items menu of the SIM application. Launch sub
 * activities and dialogs to interact with user.
 */
public class StkActivity extends ListActivity implements View.OnClickListener {

    // Members
    private AppInterface mStkService = null;
    private String mSelectedItem = null;
    private Handler mHandler = null;
    private BitSet mStkEvents = null;
    private DialogEvent mActiveMsgDialogEvent = null;
    private DialogEvent mActiveBrowserDialogEvent = null;
    private MsgDialogParams mMsgDialogParams = new MsgDialogParams();
    private MsgDialogParams mNextMsgDialogParams = new MsgDialogParams();
    private BrowserDialogParams mBrowserDialogParams = new BrowserDialogParams();
    private CallDialogParams mCallDialogParams = new CallDialogParams();

    private TonePlayer mTonePlayer = null;
    private WatchDog mTimeoutWatchDog = null;
    private Object mMsgDialogSync = null;
    private int mUiState = UI_STATE_IDLE;
    private boolean mLaunchNextDialog = false;
    private TextView mTitleText;
    private ImageView mTitleIcon;
    private com.android.internal.telephony.gsm.stk.Menu mCurrentMenu = null;

    // Constants
    private static final String TAG = "STK ACTIVITY";

    private static final String UI_STATE = "Stk.ui.state";
    private static final String STK_MENU = "Stk.ui.stkMenu";

    // Internal Activity id
    public static final int ACTIVITY_MAIN = 1;
    public static final int ACTIVITY_GET_INPUT = 2;
    public static final int ACTIVITY_GET_INKEY = 3;
    public static final int ACTIVITY_GET_INKEY_YESNO = 4;
    public static final int ACTIVITY_BROWSER = 5;
    public static final int ACTIVITY_CALL = 6;

    // Internal Dialog id
    private static final int NO_DIALOG_ID = 0;
    private static final int CALL_CONFIRM_DIALOG_ID = 1;
    private static final int CALL_SETUP_DIALOG_ID = 2;
    private static final int MSG_DIALOG_ID = 3;
    private static final int BROWSER_DIALOG_ID = 4;

    // Internal state id
    private static final int UI_STATE_IDLE = 0;
    private static final int UI_STATE_MAIN = 1;
    private static final int UI_STATE_SELECT = 2;
    private static final int UI_STATE_IN_MSG_DIALOG = 3;
    private static final int UI_STATE_IN_BROWSER_DIALOG = 4;
    private static final int UI_STATE_IN_CALL_CONFIRM_DIALOG = 5;
    private static final int UI_STATE_IN_CALL_SETUP_DIALOG = 6;
    private static final int UI_STATE_PLAY_TONE = 7;

    // Message id to signal tone duration timeout.
    private static final int STOP_TONE_MSG = 0xde;

    // Inner class, implements WatchDog.Event. track the active state of a dialog
    // Event should be set when dialog becomes inactive.
    private class DialogEvent implements Event {
        private boolean mActiveMsgDialog = true;

        public void set() {
            mActiveMsgDialog = true;
        }

        public void unSet() {
            mActiveMsgDialog = false;
        }

        public boolean isSet() {
            return mActiveMsgDialog;
        }
    }

    // Container to store dialog parameters.
    private class DialogParams {
        // Constants
        private static final String DIALOG_TEXT = "Dialog.text";
        private static final String DIALOG_TITLE = "Dialog.title";
        private static final String DIALOG_ATTR = "Dialog.attr";

        String text;
        TextAttribute attr;
        String title;
        Bitmap icon;
        // timer
        WatchDog timeoutWatchDog;

        DialogParams() {
            title = "";
            attr = null;
            text = "";
            icon = null;
            timeoutWatchDog = null;
        }

        void setTimer() {
            dismissTimer();
            timeoutWatchDog = new WatchDog(null, mHandler, null,
                    StkApp.UI_TIMEOUT);
        }

        void dismissTimer() {
            if (timeoutWatchDog != null) {
                timeoutWatchDog.cancel();
            }
        }

        DialogParams(String text, TextAttribute attr, String title, Bitmap icon) {
            this.text = text;
            this.attr = attr;
            this.title = title;
            this.icon = icon;
        }

        void packParams(Bundle bundle) {
            bundle.putString(DIALOG_TEXT, this.text);
            bundle.putString(DIALOG_TITLE, this.title);
            bundle.putBundle(DIALOG_ATTR, Util.packTextAttr(this.attr));
        }

        void unPackParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            this.text = bundle.getString(DIALOG_TEXT);
            this.title = bundle.getString(DIALOG_TITLE);
            this.attr = Util.unPackTextAttr(bundle.getBundle(DIALOG_ATTR));
        }
    }

    // Container to store message dialog parameters.
    private class MsgDialogParams extends DialogParams {
        // Constants
        private static final String MSG_DIALOG_RES = "MsgDialog.response";
        private static final String MSG_DIALOG_CONIF = "MsgDialog.confirmed";

        // Message dialog specific parameters.
        boolean responseNeeded;
        Object terminationLock;

        MsgDialogParams() {
            super();
            responseNeeded = false;
            terminationLock = new Object();
        }

        void setTimer() {
            dismissTimer();
            timeoutWatchDog = new WatchDog(null, mHandler,
                    new RunTerminateMsgDialog(), StkApp.UI_TIMEOUT);
        }

        void packParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            super.packParams(bundle);
            bundle.putBoolean(MSG_DIALOG_RES, this.responseNeeded);
        }

        void unPackParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            super.unPackParams(bundle);
            this.responseNeeded = bundle.getBoolean(MSG_DIALOG_RES);
        }
    }

    // Container to store browser dialog parameters.
    private class BrowserDialogParams extends DialogParams {
        // Constants
        private static final String BROWSER_DIALOG_URI = "BrowserDialog.uri";
        private static final String BROWSER_DIALOG_MODE = "BrowserDialog.mode";

        // Browser dialog specific parameters.
        Uri uri;
        LaunchBrowserMode mode;

        BrowserDialogParams() {
            super();
            // Set default uri.
            uri = Uri.parse("file:///android_asset/html/home.html");
            mode = LaunchBrowserMode.LAUNCH_NEW_BROWSER;
        }

        void setTimer() {
            dismissTimer();
            timeoutWatchDog = new WatchDog(null, mHandler,
                    new RunTerminateBrowserDialog(), StkApp.UI_TIMEOUT);
        }

        void packParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            super.packParams(bundle);
            if (this.uri != null) {
                bundle.putString(BROWSER_DIALOG_URI, this.uri.toString());
            }
            if (this.mode != null) {
                bundle.putInt(BROWSER_DIALOG_MODE, this.mode.ordinal());
            }
        }

        void unPackParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            super.unPackParams(bundle);
            String uriValue = bundle.getString(BROWSER_DIALOG_URI);
            if (uriValue != null) {
                this.uri = Uri.parse(uriValue);
            }
            int modeValue = bundle.getInt(BROWSER_DIALOG_MODE);
            if (modeValue != 0) {
                this.mode = LaunchBrowserMode.values() [modeValue];
            }
        }
    }

    private class CallDialogParams extends DialogParams {
        // Constants
        private static final String CALL_DIALOG_MSG = "CallDialog.message";

        String callMsg;

        CallDialogParams() {
            super();
        }

        CallDialogParams(String text, TextAttribute attr, String title,
                String callMsg) {
            super(text, attr, title, null);
            this.callMsg = callMsg;
        }

        void packParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            bundle.putString(CALL_DIALOG_MSG, callMsg);
        }

        void unPackParams(Bundle bundle) {
            if (bundle == null) {
                return;
            }
            callMsg = bundle.getString(CALL_DIALOG_MSG);
        }
    }

    // Runnable to be executed when message dialog are timed out.
    private class RunTerminateMsgDialog implements Runnable {
        public void run() {
            terminateMsgDialog(ResultCode.NO_RESPONSE_FROM_USER);
        }
    }

    // Runnable to be terminate browser confirmation dialog.
    private class RunTerminateBrowserDialog implements Runnable {
        public void run() {
            terminateBrowserDialog(false, true);
        }
    }

    // Runnable to be notify STK service user didn't respond to display text 
    // command.
    private class OnNoResponse implements Runnable {
        public void run() {
            mStkService.notifyNoResponse();
            mUiState = UI_STATE_IDLE;
        }
    }

    // Runnable to trigger refresh view on session end.
    private class RunRefeshViewOnSessionEnd implements Runnable {
        public void run() {
            refreshViewOnSessionEnd();
        }
    }

    /**
     * Handler used to stop tones from playing when the duration ends.
     */
    Handler mToneStopper = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case STOP_TONE_MSG:
                terminateTone();
                break;
            }
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        int dialogId = NO_DIALOG_ID;
        super.onCreate(icicle);

        // Remove the default title, customized one is used.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Set the layout for this activity.
        setContentView(R.layout.stk_menu_list);

        mTitleText = (TextView) findViewById(R.id.title_text);
        mTitleIcon = (ImageView) findViewById(R.id.title_icon);

        // Initialize members
        mStkService = Service.getInstance();
        if (mStkService == null) {
            Log.d(TAG, "Unable to get application handle ==> Activity stoped");
            finish();
        }

        // Instantiate members
        mHandler = new Handler();
        mMsgDialogSync = new Object();
        mActiveMsgDialogEvent = new DialogEvent();
        mActiveBrowserDialogEvent = new DialogEvent();

        // Synchronize application state with the service, only if it's on main 
        // state. This is usually true when the application is launched for the 
        // first time. Otherwise the application state will be preserved using 
        // onSaveInstanceState(...) & onRestoreInstaceState(...).
        if (mStkService.getState() == AppInterface.State.MAIN_MENU) {
            mUiState = UI_STATE_MAIN;
            mCurrentMenu = mStkService.getCurrentMenu();
        }
    }

    public void onClick(View v) {
        switch(v.getId()) {
        case R.id.button_ok:
            // used on message dialog.
            terminateMsgDialog(ResultCode.OK);
            break;
        case R.id.button_no:
            // used on launch browser dialog.
            terminateBrowserDialog(false, true);
            break;
        case R.id.button_yes:
            // used on launch browser dialog.
            terminateBrowserDialog(true, true);
            break;
        case R.id.button_call:
            // used on set up call dialog.
            terminateCallConfirmDialog(true, true);
            break;
            // used on set up call dialog.
        case R.id.button_cancel:
            terminateCallConfirmDialog(false, true);
            break;
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Setting stk service command listener to null will stop the service
        // from trying to send messages when the app is paused.
        mStkService.setCommandListener(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // Set stk service command listener to receive proactive commands.
        mStkService.setCommandListener(this.new StkCmdListener());

        // If application is resumed from call setup go back to the menu state.
        if (mUiState == UI_STATE_IN_CALL_SETUP_DIALOG) {
            switch (mStkService.getState()) {
            case SELECT_ITEM:
                mUiState = UI_STATE_SELECT;
                break;
            case MAIN_MENU:
                mUiState = UI_STATE_MAIN;
                mCurrentMenu = mStkService.getCurrentMenu();
                break;
            default:
                mStkService.terminateSession();
                return;
            }
        }

        // Render application view.
        refreshView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTimeoutWatchDog != null) {
            mTimeoutWatchDog.cancel();
        }

        // If Toneplayer was used, release it's resources.
        if (mTonePlayer != null) {
            mTonePlayer.release();
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);

        AppInterface.State state = mStkService.getState();
        Item item = getSelectedItem(position);
        if (item == null) {
            return;
        }
        switch (state) {
        case MAIN_MENU:
            // Notify the SIM about the menu selection id.
            mStkService.notifyMenuSelection(item.id, false);
            break;
        case SELECT_ITEM:
            // Terminate timeout watchdog for SELECT_ITEM.
            if (mTimeoutWatchDog != null) {
                mTimeoutWatchDog.cancel();
            }
            // Save item string for display purposes.
            mSelectedItem = mCurrentMenu.items.get(position).toString();
            // Notify the SIM about the item selection id.
            mStkService.notifySelectedItem(item.id, false);
            break;
        default:
            // If application and service are not synchronized, terminate 
            // the current session.
            mStkService.terminateSession();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            switch (mUiState) {
            case UI_STATE_SELECT:
                cancelTimeOut();
                // Signal stk service to go back, if failed go to the main menu.
                if (!mStkService.backwardMove()) {
                    mStkService.terminateSession();
                }
                mUiState = UI_STATE_IDLE;
                return true;
            case UI_STATE_MAIN:
                mUiState = UI_STATE_IDLE;
                break;
            }
            break;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        resetTimeOut();
        return super.onTrackballEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        resetTimeOut();
        return super.onTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bundle extras = null;
        boolean helpRequired = false;
        boolean yesNoResponse = false;
        String inputResponse = null;
        char inKeyResponse = 'F';
        
        if (data != null) {
            extras = data.getExtras();
        }

        switch (resultCode) {
        case StkApp.RESULT_HELP:
            helpRequired = true;
        case StkApp.RESULT_OK:
            switch (requestCode) {
            case ACTIVITY_GET_INPUT:
                if (extras != null) {
                    inputResponse = extras.getString(Util.INPUT_TYPE_TEXT);
                }
                mStkService.notifyInput(inputResponse, helpRequired);
                break;
            case ACTIVITY_GET_INKEY:
                if (extras != null) {
                    inKeyResponse = extras.getChar(Util.INPUT_TYPE_KEY);
                }
                mStkService.notifyInkey(inKeyResponse, helpRequired);
                break;
            case ACTIVITY_GET_INKEY_YESNO:
                if (extras != null) {
                    yesNoResponse = extras.getBoolean(Util.INPUT_TYPE_KEY);
                }
                mStkService.notifyInkey(yesNoResponse, helpRequired);
                break;
            case ACTIVITY_BROWSER:
                // Handle browser termination event
                if (getEventStatus(Service.UICC_EVENT_BROWSER_TERMINATION)) {
                    setEventStatus(Service.UICC_EVENT_BROWSER_TERMINATION,
                            false);
                    mStkService.notifyBrowserTermination(false);
                    break;
                }
            case ACTIVITY_CALL:
                break;
            }
            break;
        case StkApp.RESULT_TIMEDOUT:
            mStkService.notifyNoResponse();
            break;
        case StkApp.RESULT_BACKWARD:
            mStkService.backwardMove();
            break;
        case StkApp.RESULT_END_SESSION:
            mStkService.terminateSession();
            break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, StkApp.MENU_ID_MAIN, 1, R.string.sim_main_menu);
        menu.add(0, StkApp.MENU_ID_HELP, 2, R.string.help);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean helpVisible = false;
        boolean mainVisible = false;

        if (mUiState == UI_STATE_SELECT) {
            mainVisible = true;
        }
        if (mCurrentMenu != null) {
            helpVisible = mCurrentMenu.helpAvailable;
        }
        
        menu.findItem(StkApp.MENU_ID_MAIN).setVisible(mainVisible);
        menu.findItem(StkApp.MENU_ID_HELP).setVisible(helpVisible);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case StkApp.MENU_ID_MAIN:
            // Cancel timeout thread.
            cancelTimeOut();
            // Terminate SIM session
            mStkService.terminateSession();
            // Set ui state to idle.
            mUiState = UI_STATE_IDLE;
            return true;
        case StkApp.MENU_ID_HELP:
            int position = getSelectedItemPosition();
            Item stkItem = getSelectedItem(position);
            if (item == null) {
                break;
            }
            switch(mUiState) {
            case UI_STATE_MAIN:
                mStkService.notifyMenuSelection(stkItem.id, true);
                break;
            case UI_STATE_SELECT:
                mStkService.notifySelectedItem(stkItem.id, true);
                break;
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (StkApp.DBG) Log.d(TAG, "onSaveInstanceState");

        super.onSaveInstanceState(outState);

        // Setting stk service command listener to null will stop the service
        // from trying to send messages while the state is being saved. 
        // This function is also called in onPause for cases where state is not 
        // preserved. 
        mStkService.setCommandListener(null);

        outState.putInt(UI_STATE, mUiState);
        outState.putParcelable(STK_MENU, mCurrentMenu);

        // Preserve dialog parameters
        switch (mUiState) {
        case UI_STATE_IN_MSG_DIALOG:
            mMsgDialogParams.packParams(outState);
            mMsgDialogParams.responseNeeded = false;
            terminateMsgDialog();
            removeDialog(MSG_DIALOG_ID);
            break;
        case UI_STATE_IN_BROWSER_DIALOG:
            mBrowserDialogParams.packParams(outState);
            terminateBrowserDialog(false, false);
            removeDialog(BROWSER_DIALOG_ID);
            break;
        case UI_STATE_IN_CALL_CONFIRM_DIALOG:
            mCallDialogParams.packParams(outState);
            terminateCallConfirmDialog(false, false);
            removeDialog(CALL_CONFIRM_DIALOG_ID);
            break;
        case UI_STATE_IN_CALL_SETUP_DIALOG:
            dismissDialog(CALL_SETUP_DIALOG_ID);
            removeDialog(CALL_SETUP_DIALOG_ID);
            break;
        case UI_STATE_PLAY_TONE:
            mToneStopper.removeMessages(STOP_TONE_MSG);
            terminateTone();
            // Once playing the tone is done the session is ended so the application 
            // should go back to main state.
            outState.putInt(UI_STATE, UI_STATE_MAIN);
            break;
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        if (StkApp.DBG) Log.d(TAG, "onRestoreInstanceState");

        super.onRestoreInstanceState(savedInstanceState);

        mUiState = savedInstanceState.getInt(UI_STATE);
        mCurrentMenu = savedInstanceState.getParcelable(STK_MENU);

        switch (mUiState) {
        case UI_STATE_IN_MSG_DIALOG:
            MsgDialogParams m = new MsgDialogParams();
            m.unPackParams(savedInstanceState);
            prepareMsgDialog(m.text, m.attr, m.title, m.responseNeeded, m.icon);
            break;
        case UI_STATE_IN_BROWSER_DIALOG:
            mBrowserDialogParams.unPackParams(savedInstanceState);
            break;
        case UI_STATE_IN_CALL_CONFIRM_DIALOG:
            mCallDialogParams.unPackParams(savedInstanceState);
            break;
        }
    }

    private Item getSelectedItem(int position) {
        Item item = null;
        if (mCurrentMenu != null) {
            try {
                item = mCurrentMenu.items.get(position);
            } catch (IndexOutOfBoundsException e) {
                if (StkApp.DBG) {
                    Log.d(TAG, "Invalid menu");
                }
            } catch (NullPointerException e) {
                if (StkApp.DBG) {
                    Log.d(TAG, "Invalid menu");
                }
            }
        }
        return item;
    }

    // Bind list adapter to the items list.
    private void displayMenu() {

        if (mCurrentMenu != null) {
            // create an array adapter for the menu list
            StkMenuAdapter adapter = new StkMenuAdapter(this,
                    mCurrentMenu.items, mCurrentMenu.itemsIconSelfExplanatory);
            // Bind menu list to the new adapter.
            setListAdapter(adapter);

            // Display title & title icon
            if (mCurrentMenu.titleIcon != null) {
                mTitleIcon.setImageBitmap(mCurrentMenu.titleIcon);
            } else {
                mTitleIcon.setVisibility(View.GONE);
            }
            if (!mCurrentMenu.titleIconSelfExplanatory) {
                if (mCurrentMenu.title == null) {
                    mTitleText.setText(R.string.app_name);
                } else {
                    mTitleText.setText(mCurrentMenu.title);
                }
            }
            // Set default item
            setSelection(mCurrentMenu.defaultItem);
        }
    }

    private void refreshView() {
        displayMenu();

        // In case a dialog needs to be refreshed.
        switch (mUiState) {
        case UI_STATE_IN_MSG_DIALOG:
            launchMsgDialog();
            break;
        case UI_STATE_IN_BROWSER_DIALOG:
            launchBrowserDialog();
            break;
        case UI_STATE_IN_CALL_CONFIRM_DIALOG:
            launchCallConfirmDialog();
            break;
        default:
            return;
        }
    }

    private void refreshViewOnSessionEnd() {
        cancelTimeOut();
        switch (mStkService.getState()) {
        case MAIN_MENU:
            mUiState = UI_STATE_MAIN;
            mCurrentMenu = mStkService.getCurrentMenu();
            refreshView();
            break;
        case IDLE:
            finish();
            break;
        }
    }

    private void resetTimeOut() {
        if (mTimeoutWatchDog != null) {
            // Reset timeout.
            mTimeoutWatchDog.reset();
        }
    }

    private void cancelTimeOut() {
        if (mTimeoutWatchDog != null) {
            // Reset timeout.
            mTimeoutWatchDog.cancel();
        }
    }

    private void pauseTimeOut() {
        if (mTimeoutWatchDog != null) {
            // Reset timeout.
            mTimeoutWatchDog.pause();
        }
    }

    private void resumeTimeOut() {
        if (mTimeoutWatchDog != null) {
            // Reset timeout.
            mTimeoutWatchDog.unpause();
        }
    }

    private void prepareDialog(DialogParams params, String text,
            TextAttribute attr, String title, Bitmap icon) {
        if (params == null) return;

        params.text = text;
        params.attr = attr;
        params.title = title;
        params.icon = icon;
    }

    // Set text dialog parameters into a member.
    private void prepareMsgDialog(String text, TextAttribute attrs,
            String title, boolean responseNeeded, Bitmap icon) {
        synchronized (mMsgDialogSync) {
            prepareDialog(mNextMsgDialogParams, text, attrs, title, icon);
            mNextMsgDialogParams.responseNeeded = responseNeeded;
        }
    }

    // Set text dialog parameters into a member.
    private void prepareBrowserDialog(String text, TextAttribute attrs,
            String title, Uri uri, LaunchBrowserMode mode) {

        prepareDialog(mBrowserDialogParams, text, attrs, title, null);
        mBrowserDialogParams.uri = uri;
        mBrowserDialogParams.mode = mode;
    }

    private void prepareCallDialog(String text, TextAttribute attrs,
            String title, String callMsg) {
        prepareDialog(mCallDialogParams, text, attrs, title, null);
        mCallDialogParams.callMsg = callMsg;
    }

    // Opens display text dialog. MUST be preceded by prepareMsgDialog(...)
    private void launchMsgDialog() {
        // Pause timeout thread for Select Item.
        pauseTimeOut();
        mUiState = UI_STATE_IN_MSG_DIALOG;
        mActiveMsgDialogEvent.unSet();
        synchronized (mMsgDialogSync) {
            mMsgDialogParams = mNextMsgDialogParams;
        }
        mMsgDialogParams.setTimer();
        showDialog(MSG_DIALOG_ID);
    }

    // Opens display browser confirmation dialog. MUST be preceded by
    // prepareBrowserDialog(...)
    private void launchBrowserDialog() {
        mUiState = UI_STATE_IN_BROWSER_DIALOG;
        mActiveBrowserDialogEvent.unSet();
        mBrowserDialogParams.setTimer();
        showDialog(BROWSER_DIALOG_ID);
    }

    // Opens call/cancel confirmation dialog
    private void launchCallConfirmDialog() {
        mUiState = UI_STATE_IN_CALL_CONFIRM_DIALOG;
        showDialog(CALL_CONFIRM_DIALOG_ID);
    }

    // Opens setup call dialog
    private void launchCallSetupDialog() {
        mUiState = UI_STATE_IN_CALL_SETUP_DIALOG;
        showDialog(CALL_SETUP_DIALOG_ID);
    }

    // Same as terminateMsgDialog(), with additional ResultCode send to the STK 
    // Service.
    private void terminateMsgDialog(ResultCode terminationCode) {
        terminateMsgDialog();
        if (mMsgDialogParams.responseNeeded) {
            mStkService.notifyDisplayTextEnded(terminationCode);
        }
    }

    private void terminateMsgDialog() {
        synchronized (mMsgDialogParams.terminationLock) {
            dismissDialog(MSG_DIALOG_ID);
            mMsgDialogParams.dismissTimer();
            // Resume timeout thread for Select Item.
            resumeTimeOut();
            if (mLaunchNextDialog == true) {
                synchronized (this) {
                    mLaunchNextDialog = false;
                }
                launchMsgDialog();
            } else {
                mUiState = UI_STATE_IDLE;
                // signal message dialog event.
                mActiveMsgDialogEvent.set();
            }
        }
    }

    private void terminateBrowserDialog(boolean userConfirmed,
            boolean notifyService) {
        dismissDialog(BROWSER_DIALOG_ID);
        mBrowserDialogParams.dismissTimer();
        if (userConfirmed) {
            launchBrowser(mBrowserDialogParams.uri, mBrowserDialogParams.mode);
        }
        if (notifyService) {
            mStkService.notifyLaunchBrowser(userConfirmed);
        }
        mUiState = UI_STATE_IDLE;
        // signal browser dialog event.
        mActiveBrowserDialogEvent.set();
    }

    private void terminateCallConfirmDialog(boolean call, boolean notifyService) {
        dismissDialog(CALL_CONFIRM_DIALOG_ID);
        if (call) {
            if (mCallDialogParams.callMsg == null) {
                mCallDialogParams.callMsg = getString(R.string.default_call_setup_msg);
            }
            launchCallSetupDialog(); 
        } else {
            mUiState = UI_STATE_IDLE;
        }
        if (notifyService) {
            mStkService.acceptOrRejectCall(call);
        }
    }

    private void terminateTone() {
        if (mTonePlayer != null) {
            mTonePlayer.stop();
            mStkService.notifyToneEnded();
        }
        mUiState = UI_STATE_IDLE;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Dialog dialog = null;
        if (id == CALL_SETUP_DIALOG_ID) {
            dialog = new ProgressDialog(this);
            dialog.setCancelable(false);
            ((ProgressDialog) dialog).setMessage(mCallDialogParams.callMsg);
            ((ProgressDialog) dialog).setIndeterminate(true);
        } else {
            dialog = new Dialog(this);

            switch (id) {
            case MSG_DIALOG_ID:
                dialog.setContentView(R.layout.stk_msg_dialog);
                break;
            case BROWSER_DIALOG_ID:
                dialog.setContentView(R.layout.stk_input);

                View yesNoLayout = dialog.findViewById(R.id.yes_no_layout);
                View normalLayout = dialog.findViewById(R.id.normal_layout);

                yesNoLayout.setVisibility(View.VISIBLE);
                normalLayout.setVisibility(View.GONE);
                break;
            case CALL_CONFIRM_DIALOG_ID:
                dialog.setContentView(R.layout.stk_call_dialog);
                break;
            }
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        DialogParams currentDialogParams = null;
        TextView promptView = null;
        ImageView imageView = null;

        switch (id) {
        case MSG_DIALOG_ID:
            currentDialogParams = mMsgDialogParams;
            promptView = (TextView) dialog.findViewById(R.id.dialog_message);
            imageView = (ImageView) dialog.findViewById(R.id.dialog_icon);
            Button b = (Button) dialog.findViewById(R.id.button_ok);
            b.setOnClickListener(this);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                public boolean onKey(DialogInterface dialog, int keyCode,
                        KeyEvent event) {
                    switch (keyCode) {
                    case KeyEvent.KEYCODE_BACK:
                        terminateMsgDialog(ResultCode.BACKWARD_MOVE_BY_USER);
                        break;
                    }
                    return false;
                }
            });
            break;
        case CALL_CONFIRM_DIALOG_ID:
            currentDialogParams = mCallDialogParams;
            promptView = (TextView) dialog.findViewById(R.id.prompt);
            Button call = (Button) dialog.findViewById(R.id.button_call);
            Button cancel = (Button) dialog.findViewById(R.id.button_cancel);
            call.setOnClickListener(this);
            cancel.setOnClickListener(this);
            dialog.setCancelable(false);
            break;
        case BROWSER_DIALOG_ID:
            currentDialogParams = mBrowserDialogParams;
            promptView = (TextView) dialog.findViewById(R.id.prompt);
            Button y = (Button) dialog.findViewById(R.id.button_yes);
            Button n = (Button) dialog.findViewById(R.id.button_no);
            y.setOnClickListener(this);
            n.setOnClickListener(this);
            dialog.setCancelable(false);
            break;
        default:
            return;
        }
        // Set prompt and title.
        promptView.setText(currentDialogParams.text);
        // Set title if present... if not remove it from the layout.
        if (currentDialogParams.title != null) {
            dialog.setTitle(currentDialogParams.title);
        } else {
            View t = dialog.findViewById(android.R.id.title);
            t.setVisibility(View.GONE);
        }
        if(currentDialogParams.icon != null) {
            imageView.setImageBitmap(currentDialogParams.icon);
        } else if (imageView != null) {
            imageView.setVisibility(View.GONE);
        }
    }

    // Opens the browser
    private void launchBrowser(Uri uri, LaunchBrowserMode mode) {
        // Set browser launch mode
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName("com.android.browser",
                "com.android.browser.BrowserActivity");
        intent.setData(uri);
        intent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        switch (mode) {
        case USE_EXISTING_BROWSER:
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        case LAUNCH_NEW_BROWSER:
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            break;
        case LAUNCH_IF_NOT_ALREADY_LAUNCHED:
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            break;
        }
        // start browser activity
        startActivity(intent);
    }

    private void updateEventsSet(BitSet events) {
        mStkEvents.or(events);
    }

    private void setEventStatus(int event, boolean state) {
        if (mStkEvents == null) return;

        mStkEvents.set(event, state);
    }

    private boolean getEventStatus(int event) {
        if (mStkEvents == null) return false;

        return mStkEvents.get(event);
    }

    // Private inner class, implements CommandListener I/F to get callback calls
    // from the STK service.
    private class StkCmdListener implements CommandListener {

        public void onCallSetup(String confirmMsg, List<TextAttribute> textAttrs,
                String callMsg) {
            if (StkApp.DBG) Log.d(TAG, "onCallSetup");

            TextAttribute attr = null;
            String title = getString(R.string.launch_dialer);
            
            if (textAttrs != null && textAttrs.size() > 0) {
                attr = textAttrs.get(0);
            }
            // Launch call confirmation dialog.
            prepareCallDialog(confirmMsg, attr, title, callMsg);
            launchCallConfirmDialog();
        }

       public void onDisplayText(String text, List<TextAttribute> textAttrs,
                boolean isHighPriority, boolean userClear,
                boolean responseNeeded, Bitmap icon) {

            if (StkApp.DBG) Log.d(TAG, "onDisplayText: " + text);

            // Store dialog parameters to be used just before dialog creation.
            TextAttribute attr = null;
            if (textAttrs != null && textAttrs.size() > 0) {
                attr = textAttrs.get(0);
            }
            prepareMsgDialog(text, attr, null, responseNeeded, icon);

            // If there is an active message dialog, signal to launch a new dialog 
            // when the current one is done.
            if (mUiState == UI_STATE_IN_MSG_DIALOG) {
                synchronized (StkActivity.this) {
                    mLaunchNextDialog = true;
                }
                if (isHighPriority) {
                    terminateMsgDialog(ResultCode.OK);
                }
            } else {
                launchMsgDialog();
            }
        }

        public void onSetUpMenu(com.android.internal.telephony.gsm.stk.Menu menu) {
            if (StkApp.DBG) Log.d(TAG, "onSetUpMenu");

            mUiState = UI_STATE_MAIN; 
            mCurrentMenu = menu; 
            displayMenu();
        }

        public void onGetInkey(String text, List<TextAttribute> textAttrs,
                boolean yesNo, boolean digitOnly, boolean ucs2,
                boolean immediateResponse, boolean helpAvailable) {

            if (StkApp.DBG) Log.d(TAG, "onGetInkey" + text);

            Intent intent = new Intent(StkActivity.this, StkInputActivity.class);
            int subActivityId = yesNo ? ACTIVITY_GET_INKEY_YESNO
                    : ACTIVITY_GET_INKEY;

            // put command data inside the intent.
            intent.putExtra(Util.INPUT_TYPE, Util.INPUT_TYPE_KEY);
            intent.putExtra(Util.INPUT_PROMPT, text);

            if (textAttrs != null && textAttrs.size() > 0) {
                TextAttribute attr = textAttrs.get(0);
                Bundle texttAttrBundle;
                if (attr != null) {
                    texttAttrBundle = Util.packTextAttr(attr);
                    intent.putExtra(Util.INPUT_TEXT_ATTRS, texttAttrBundle);
                }
            }
            // Pack the global input attributes into the intent.
            Bundle glblAttrBundle = new Bundle();
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_YES_NO, yesNo);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_DIGITS, digitOnly);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_UCS2, ucs2);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_IMD_RESPONSE,
                    immediateResponse);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_HELP, helpAvailable);
            intent.putExtra(Util.INPUT_GLBL_ATTRS, glblAttrBundle);

            // Start the input sub activity
            startActivityForResult(intent, subActivityId);
        }

        public void onGetInput(String text, String defaultText, int minLen,
                int maxLen, boolean noMaxLimit, List<TextAttribute> textAttrs,
                boolean digitOnly, boolean ucs2, boolean echo,
                boolean helpAvailable) {

            if (StkApp.DBG) Log.d(TAG, "onGetInput: " + text);

            Intent intent = new Intent(StkActivity.this, StkInputActivity.class);
            // put command data inside the intent.
            intent.putExtra(Util.INPUT_TYPE, Util.INPUT_TYPE_TEXT);
            intent.putExtra(Util.INPUT_PROMPT, text);
            if (defaultText != null) {
                intent.putExtra(Util.INPUT_DEFAULT, defaultText);
            }

            // Pack text attributes into the intent.
            if (textAttrs != null && textAttrs.size() > 0) {
                TextAttribute attr = textAttrs.get(0);
                if (attr != null) {
                    Bundle texttAttrBundle = Util.packTextAttr(attr);
                    intent.putExtra(Util.INPUT_TEXT_ATTRS, texttAttrBundle);
                }
            }
            // Pack the global input attributes into the intent.
            Bundle glblAttrBundle = new Bundle();
            glblAttrBundle.putInt(Util.INPUT_ATTR_MINLEN, minLen);
            glblAttrBundle.putInt(Util.INPUT_ATTR_MAXLEN, maxLen);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_NOMAAXLIM, noMaxLimit);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_DIGITS, digitOnly);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_UCS2, ucs2);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_ECHO, echo);
            glblAttrBundle.putBoolean(Util.INPUT_ATTR_HELP, helpAvailable);
            intent.putExtra(Util.INPUT_GLBL_ATTRS, glblAttrBundle);

            // Start the input sub activity
            startActivityForResult(intent, ACTIVITY_GET_INPUT);
        }

        public void onSelectItem(com.android.internal.telephony.gsm.stk.Menu menu, 
                PresentationType presentationType) {

            if (StkApp.DBG) Log.d(TAG, "onSelectItem: " + menu.title);

            mCurrentMenu = menu;

            // If activity is already inside a message dialog, launch the next 
            // item list when that message is dismissed. 
            if (mUiState != UI_STATE_IN_MSG_DIALOG) {
                mUiState = UI_STATE_SELECT;
                displayMenu();
                // launch timeout watchdog to signal no response from user.
                mTimeoutWatchDog = new WatchDog(null, mHandler,
                        new OnNoResponse(), StkApp.UI_TIMEOUT);
            } else {
                new WatchDog(mActiveMsgDialogEvent, mHandler, new Runnable() {
                    public void run() {
                        mUiState = UI_STATE_SELECT;
                        displayMenu();
                        // launch timeout wathdog to signal no response from
                        // user.
                        mTimeoutWatchDog = new WatchDog(null, mHandler,
                                new OnNoResponse(), StkApp.UI_TIMEOUT);
                    }
                }, WatchDog.TIMEOUT_WAIT_FOREVER);
            } 
        }

        public void onSetUpEventList(BitSet events) throws ResultException {
            if (StkApp.DBG) Log.d(TAG, "onSetUpEventList");

            if (events == null || events.isEmpty()) {
                throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
            }

            updateEventsSet(events);
        }

        public void onLaunchBrowser(String url, String confirmMsg,
                List<TextAttribute> confirmMsgAttrs,
                final LaunchBrowserMode mode) {
            if (StkApp.DBG) Log.d(TAG, "onLaunchBrowser: " + url);

            TextAttribute attrs = null;
            String title = getString(R.string.launch_browser);
            Uri uri = url == null ? null : Uri.parse(url);

            // Launch browser confirmation dialog.
            if (confirmMsg != null) {
                if (confirmMsgAttrs != null && confirmMsgAttrs.size() > 0) {
                    attrs = confirmMsgAttrs == null ? null : confirmMsgAttrs
                            .get(0);
                }
                prepareBrowserDialog(confirmMsg, attrs, title, uri, mode);
                launchBrowserDialog();
            } else {
                mStkService.notifyLaunchBrowser(true);
                launchBrowser(uri, mode);
            }
        }

        public void onPlayTone(Tone tone, String text,
                List<TextAttribute> textAttrs, Duration duration)
                throws ResultException {
            if (StkApp.DBG) Log.d(TAG, "onPlayTone:" + tone + " Message:" + text);

            if (text != null) {
                String title = getString(R.string.play_tone);
                TextAttribute attrs = textAttrs == null ? null : textAttrs
                        .get(0);
                prepareMsgDialog(text, attrs, title, false, null);
                launchMsgDialog();
            }
            if (mTonePlayer == null) {
                mTonePlayer = new TonePlayer();
            }
            mTonePlayer.play(tone);
            int timeout = StkApp.calculateToneDuration(duration);
            mToneStopper.sendEmptyMessageDelayed(STOP_TONE_MSG, timeout);
            mUiState = UI_STATE_PLAY_TONE;
        }

        public void onSessionEnd() {
            if (StkApp.DBG) Log.d(TAG, "onSessionEnd");

            // If any message dialog is active wait until it is finish before
            // the refreshing the view.
            synchronized (mMsgDialogParams.terminationLock) {
                if (mUiState == UI_STATE_IN_MSG_DIALOG) {
                    new WatchDog(mActiveMsgDialogEvent,
                            StkActivity.this.mHandler,
                            new RunRefeshViewOnSessionEnd(),
                            WatchDog.TIMEOUT_WAIT_FOREVER);
                } else {
                    refreshViewOnSessionEnd();
                }
            }
        }
    }
}
