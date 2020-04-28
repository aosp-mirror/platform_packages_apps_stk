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

import android.app.ActionBar;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;

import android.telephony.TelephonyManager;

import java.util.ArrayList;

/**
 * Launcher class. Serve as the app's MAIN activity, send an intent to the
 * StkAppService and finish.
 *
 */
public class StkLauncherActivity extends ListActivity {
    private TextView mTitleTextView = null;
    private ImageView mTitleIconView = null;
    private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = className.substring(className.lastIndexOf('.') + 1);
    private ArrayList<Item> mStkMenuList = null;
    private int mSingleSimId = -1;
    private Context mContext = null;
    private TelephonyManager mTm = null;
    private Bitmap mBitMap = null;
    private boolean mAcceptUsersInput = true;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        CatLog.d(LOG_TAG, "onCreate+");
        mContext = getBaseContext();
        mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);

        ActionBar actionBar = getActionBar();
        actionBar.setCustomView(R.layout.stk_title);
        actionBar.setDisplayShowCustomEnabled(true);

        setContentView(R.layout.stk_menu_list);
        mTitleTextView = (TextView) findViewById(R.id.title_text);
        mTitleIconView = (ImageView) findViewById(R.id.title_icon);
        mTitleTextView.setText(R.string.app_name);
        mBitMap = BitmapFactory.decodeResource(getResources(),
                R.drawable.ic_launcher_sim_toolkit);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);
        if (!mAcceptUsersInput) {
            CatLog.d(LOG_TAG, "mAcceptUsersInput:false");
            return;
        }
        int simCount = TelephonyManager.from(mContext).getSimCount();
        Item item = getSelectedItem(position);
        if (item == null) {
            CatLog.d(LOG_TAG, "Item is null");
            return;
        }
        CatLog.d(LOG_TAG, "launch stk menu id: " + item.id);
        if (item.id >= PhoneConstants.SIM_ID_1 && item.id < simCount) {
            mAcceptUsersInput = false;
            launchSTKMainMenu(item.id);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        CatLog.d(LOG_TAG, "mAcceptUsersInput: " + mAcceptUsersInput);
        if (!mAcceptUsersInput) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                CatLog.d(LOG_TAG, "KEYCODE_BACK.");
                mAcceptUsersInput = false;
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onResume() {
        super.onResume();
        CatLog.d(LOG_TAG, "onResume");
        mAcceptUsersInput = true;
        int itemSize = addStkMenuListItems();
        if (itemSize == 0) {
            CatLog.d(LOG_TAG, "item size = 0 so finish.");
            finish();
        } else if (itemSize == 1) {
            launchSTKMainMenu(mSingleSimId);
            finish();
        } else {
            CatLog.d(LOG_TAG, "resume to show multiple stk list.");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CatLog.d(LOG_TAG, "onDestroy");
    }

    private Item getSelectedItem(int position) {
        Item item = null;
        if (mStkMenuList != null) {
            try {
                item = mStkMenuList.get(position);
            } catch (IndexOutOfBoundsException e) {
                if (StkApp.DBG) {
                    CatLog.d(LOG_TAG, "IOOBE Invalid menu");
                }
            } catch (NullPointerException e) {
                if (StkApp.DBG) {
                    CatLog.d(LOG_TAG, "NPE Invalid menu");
                }
            }
        }
        return item;
    }

    private int addStkMenuListItems() {
        StkAppService appService = StkAppService.getInstance();
        if (appService == null) {
            return 0;
        }

        String appName = mContext.getResources().getString(R.string.app_name);
        String stkItemName = null;
        int simCount = TelephonyManager.from(mContext).getSimCount();
        mStkMenuList = new ArrayList<Item>();

        CatLog.d(LOG_TAG, "simCount: " + simCount);
        for (int i = 0; i < simCount; i++) {
            // Check if the card is inserted.
            if (mTm.hasIccCard(i)) {
                Menu menu = appService.getMainMenu(i);
                // Check if the card has a main menu.
                if (menu != null) {
                    CatLog.d(LOG_TAG, "SIM #" + (i + 1) + " is add to menu.");
                    mSingleSimId = i;
                    stkItemName = new StringBuilder(menu.title == null ? appName : menu.title)
                            .append(" ").append(Integer.toString(i + 1)).toString();
                    // Display the default application icon if there is no icon specified by SET-UP
                    // MENU command nor preset.
                    Bitmap icon = mBitMap;
                    if (menu.titleIcon != null) {
                        icon = menu.titleIcon;
                        if (menu.titleIconSelfExplanatory) {
                            stkItemName = null;
                        }
                    }
                    Item item = new Item(i, stkItemName, icon);
                    mStkMenuList.add(item);
                } else {
                    CatLog.d(LOG_TAG, "SIM #" + (i + 1) + " does not have main menu.");
                }
            } else {
                CatLog.d(LOG_TAG, "SIM #" + (i + 1) + " is not inserted.");
            }
        }
        if (mStkMenuList != null && mStkMenuList.size() > 0) {
            if (mStkMenuList.size() > 1) {
                StkMenuAdapter adapter = new StkMenuAdapter(this,
                        mStkMenuList, false);
                // Bind menu list to the new adapter.
                this.setListAdapter(adapter);
            }
            return mStkMenuList.size();
        } else {
            CatLog.d(LOG_TAG, "No stk menu item add.");
            return 0;
        }
    }
    private void launchSTKMainMenu(int slodId) {
        Bundle args = new Bundle();
        CatLog.d(LOG_TAG, "launchSTKMainMenu.");
        args.putInt(StkAppService.OPCODE, StkAppService.OP_LAUNCH_APP);
        args.putInt(StkAppService.SLOT_ID
                , PhoneConstants.SIM_ID_1 + slodId);
        startService(new Intent(this, StkAppService.class)
                .putExtras(args));
    }
}
