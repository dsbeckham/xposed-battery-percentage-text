package com.extrinsic.batterypercentagetext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.extrinsic.batterypercentagetext.SettingsFragment.ACTION_PREF_LOCK_SCREEN_SETTING_CHANGED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.ACTION_PREF_NOTIFICATION_SHADE_HEADER_CHANGED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.ACTION_PREF_STATUS_BAR_SETTING_CHANGED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.EXTRA_LOCK_SCREEN_SETTING_ENABLED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.EXTRA_NOTIFICATION_SHADE_HEADER_ENABLED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.EXTRA_STATUS_BAR_SETTING_ENABLED;
import static com.extrinsic.batterypercentagetext.SettingsFragment.PREF_LOCK_SCREEN_SETTING;
import static com.extrinsic.batterypercentagetext.SettingsFragment.PREF_NOTIFICATION_SHADE_HEADER_SETTING;
import static com.extrinsic.batterypercentagetext.SettingsFragment.PREF_STATUS_BAR_SETTING;

public class BatteryPercentageText implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private boolean batteryCharging;
    private boolean deviceRunningCyanogenMod;

    private boolean lockScreenSettingEnabled;
    private boolean notificationShadeHeaderSettingEnabled;
    private boolean statusBarSettingEnabled;

    private Context keyguardStatusBarViewClassContext;
    private TextView lockScreenTextView;
    private TextView notificationShadeHeaderTextView;
    private TextView statusBarTextView;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XSharedPreferences sharedPreferences = new XSharedPreferences("com.extrinsic.batterypercentagetext");
        sharedPreferences.makeWorldReadable();
        lockScreenSettingEnabled = sharedPreferences.getBoolean(PREF_LOCK_SCREEN_SETTING, true);
        notificationShadeHeaderSettingEnabled = sharedPreferences.getBoolean(PREF_NOTIFICATION_SHADE_HEADER_SETTING, true);
        statusBarSettingEnabled = sharedPreferences.getBoolean(PREF_STATUS_BAR_SETTING, true);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.android.systemui")) {
            try {
                final Class<?> keyguardStatusBarViewClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.KeyguardStatusBarView", lpparam.classLoader);

                try {
                    if (XposedHelpers.findClass("com.android.systemui.BatteryLevelTextView", lpparam.classLoader) != null) {
                        deviceRunningCyanogenMod = true;
                    }
                } catch (Throwable t) {
                    deviceRunningCyanogenMod = false;
                }

                XposedBridge.hookAllConstructors(keyguardStatusBarViewClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        IntentFilter intentFilter = new IntentFilter();
                        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
                        intentFilter.addAction(ACTION_PREF_LOCK_SCREEN_SETTING_CHANGED);
                        intentFilter.addAction(ACTION_PREF_NOTIFICATION_SHADE_HEADER_CHANGED);
                        intentFilter.addAction(ACTION_PREF_STATUS_BAR_SETTING_CHANGED);

                        keyguardStatusBarViewClassContext = (Context) param.args[0];
                        keyguardStatusBarViewClassContext.registerReceiver(mBroadcastReceiver, intentFilter);
                    }
                });

                XposedHelpers.findAndHookMethod(keyguardStatusBarViewClass, "onFinishInflate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (deviceRunningCyanogenMod) {
                            // CyanogenMod uses a separate class (BatteryLevelTextView) for managing
                            // the battery percentage text. Since this text can be disabled in the
                            // CyanogenMod settings, we will just inject our own here instead.
                            ViewGroup viewGroup = (ViewGroup) ((ViewGroup) param.thisObject).findViewById(keyguardStatusBarViewClassContext.getResources().getIdentifier("system_icons", "id", "com.android.systemui"));
                            if (lockScreenTextView == null) {
                                lockScreenTextView = new TextView(viewGroup.getContext());
                                lockScreenTextView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                                lockScreenTextView.setPadding(20, 0, 0, 0);
                                lockScreenTextView.setTextColor(Color.WHITE);
                                lockScreenTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

                                if (lockScreenSettingEnabled) {
                                    lockScreenTextView.setVisibility(View.VISIBLE);
                                } else {
                                    if (!batteryCharging) {
                                        lockScreenTextView.setVisibility(View.GONE);
                                    }
                                }

                                viewGroup.addView(lockScreenTextView, viewGroup.getChildCount());
                            }
                        } else {
                            // If the device is not running CyanogenMod, then we can just use the
                            // native battery percentage text that is displayed when the device is
                            // charging.
                            lockScreenTextView = (TextView) XposedHelpers.getObjectField(param.thisObject, "mBatteryLevel");
                        }

                        XposedHelpers.callMethod(param.thisObject, "updateVisibilities");
                    }
                });

                XposedHelpers.findAndHookMethod(keyguardStatusBarViewClass, "updateVisibilities", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if ((!batteryCharging || deviceRunningCyanogenMod) && lockScreenSettingEnabled) {
                            if (lockScreenTextView != null) {
                                lockScreenTextView.setVisibility(View.VISIBLE);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }

            try {
                final Class<?> statusBarHeaderViewClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.StatusBarHeaderView", lpparam.classLoader);

                XposedHelpers.findAndHookMethod(statusBarHeaderViewClass, "onFinishInflate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        ViewGroup viewGroup = (ViewGroup) ((ViewGroup) param.thisObject).findViewById(context.getResources().getIdentifier("system_icons", "id", "com.android.systemui"));

                        if (notificationShadeHeaderTextView == null) {
                            notificationShadeHeaderTextView = new TextView(viewGroup.getContext());
                            notificationShadeHeaderTextView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                            notificationShadeHeaderTextView.setPadding(20, 0, 0, 0);
                            notificationShadeHeaderTextView.setTextColor(Color.WHITE);
                            notificationShadeHeaderTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);

                            if (notificationShadeHeaderSettingEnabled) {
                                notificationShadeHeaderTextView.setVisibility(View.VISIBLE);
                            } else {
                                notificationShadeHeaderTextView.setVisibility(View.GONE);
                            }

                            viewGroup.addView(notificationShadeHeaderTextView, viewGroup.getChildCount());
                        }
                    }
                });

                XposedHelpers.findAndHookMethod(statusBarHeaderViewClass, "updateVisibilities", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (notificationShadeHeaderSettingEnabled) {
                            if (notificationShadeHeaderTextView != null) {
                                // The existing battery percentage text here should always be hidden
                                // since we have injected our own.
                                TextView textView = (TextView) XposedHelpers.getObjectField(param.thisObject, "mBatteryLevel");
                                textView.setVisibility(View.GONE);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }

            try {
                final Class<?> phoneStatusBarClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.PhoneStatusBar", lpparam.classLoader);

                XposedHelpers.findAndHookMethod(phoneStatusBarClass, "makeStatusBarView", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        ViewGroup viewGroup = (ViewGroup) ((ViewGroup) XposedHelpers.getObjectField(param.thisObject, "mStatusBarView")).findViewById(context.getResources().getIdentifier("signal_cluster", "id", "com.android.systemui"));

                        if (statusBarTextView == null) {
                            statusBarTextView = new TextView(viewGroup.getContext());
                            statusBarTextView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                            statusBarTextView.setPadding(20, 0, 0, 0);
                            statusBarTextView.setTextColor(Color.WHITE);
                            statusBarTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                            statusBarTextView.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));

                            if (statusBarSettingEnabled) {
                                statusBarTextView.setVisibility(View.VISIBLE);
                            } else {
                                statusBarTextView.setVisibility(View.GONE);
                            }

                            viewGroup.addView(statusBarTextView, viewGroup.getChildCount());
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    final int level = (int) (100.0 * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                    final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                    batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

                    if (lockScreenTextView != null) {
                        lockScreenTextView.setText(String.format("%s%%", level));
                    }

                    if (notificationShadeHeaderTextView != null) {
                        notificationShadeHeaderTextView.setText(String.format("%s%%", level));
                    }

                    if (statusBarTextView != null) {
                        statusBarTextView.setText(String.format("%s%%", level));
                    }
                    break;
                case ACTION_PREF_LOCK_SCREEN_SETTING_CHANGED:
                    if (intent.hasExtra(EXTRA_LOCK_SCREEN_SETTING_ENABLED)) {
                        lockScreenSettingEnabled = intent.getBooleanExtra(EXTRA_LOCK_SCREEN_SETTING_ENABLED, false);

                        if (lockScreenSettingEnabled) {
                            if (lockScreenTextView != null) {
                                lockScreenTextView.setVisibility(View.VISIBLE);
                            }
                        } else {
                            if (!batteryCharging || deviceRunningCyanogenMod) {
                                if (lockScreenTextView != null) {
                                    lockScreenTextView.setVisibility(View.GONE);
                                }
                            }
                        }
                    }
                    break;
                case ACTION_PREF_NOTIFICATION_SHADE_HEADER_CHANGED:
                    if (intent.hasExtra(EXTRA_NOTIFICATION_SHADE_HEADER_ENABLED)) {
                        notificationShadeHeaderSettingEnabled = intent.getBooleanExtra(EXTRA_NOTIFICATION_SHADE_HEADER_ENABLED, false);

                        if (notificationShadeHeaderSettingEnabled) {
                            if (notificationShadeHeaderTextView != null) {
                                notificationShadeHeaderTextView.setVisibility(View.VISIBLE);
                            }
                        } else {
                            if (notificationShadeHeaderTextView != null) {
                                notificationShadeHeaderTextView.setVisibility(View.GONE);
                            }
                        }
                    }
                    break;
                case ACTION_PREF_STATUS_BAR_SETTING_CHANGED:
                    if (intent.hasExtra(EXTRA_STATUS_BAR_SETTING_ENABLED)) {
                        statusBarSettingEnabled = intent.getBooleanExtra(EXTRA_STATUS_BAR_SETTING_ENABLED, false);

                        if (statusBarSettingEnabled) {
                            if (statusBarTextView != null) {
                                statusBarTextView.setVisibility(View.VISIBLE);
                            }
                        } else {
                            if (statusBarTextView != null) {
                                statusBarTextView.setVisibility(View.GONE);
                            }
                        }
                    }
                    break;
            }
        }
    };
}
