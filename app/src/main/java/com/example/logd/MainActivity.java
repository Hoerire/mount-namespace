package com.example.logd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
// android.os.Process used via fully-qualified name to avoid conflict with java.lang.Process
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    static final String ASSET = "logd_arm64-v8a";
    static final int DANGER = -2075809;
    static final int DANGER_SOFT = -136981;
    static final String DEF_PATH = "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/data/local/bin";
    static final String EXE_NAME = "logd";
    static final int LOG_MAX_SIZE = 65536;
    static final int OFF = -3682599;
    static final int OK = -12864909;
    static final int PRIMARY = -10777105;
    static final int PRIMARY_SOFT = -1510913;
    static final int RIPPLE = 861638127;
    static final String SCRIPT_ENV = "export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/data/local/bin HOME=/data/local/tmp LANG=en_US.UTF-8 TERM=xterm";
    static final int SUBTEXT = -8616300;
    static final int TEXT = -13881032;

    File cfgFile;
    File pathsLogFile;
    File hiddenAppsFile;
    TextView floatToast;
    LinearLayout hideDirContent;
    LinearLayout hideDirList;
    TextView hideDirPill;
    ScrollView hideDirScroll;
    TextView hideListPill;
    LinearLayout homeContent;
    ScrollView homeScroll;
    LinearLayout listBox;
    File logFile;
    ScrollView logScroll;
    TextView logView;
    LinearLayout pickerContent;
    AlertDialog pickerDialog;
    EditText pickerInput;
    ScrollView pickerScroll;
    TextView pickerTitle;
    FrameLayout root;
    LinearLayout scriptContent;
    LinearLayout scriptFileBox;
    EditText scriptPathInput;
    File scriptPathPref;
    TextView scriptPill;
    ScrollView scriptScroll;
    EditText search;
    View statusDot;
    TextView statusSub;
    TextView statusText;
    Switch sysSwitch;
    EditText termInput;
    TextView termOut;
    ScrollView termScroll;
    Button termSendBtn;
    LinearLayout terminalContent;
    Button hideBtn;
    Button restoreBtn;
    Button toggleBtn;

    final ArrayList<String> hideDirs = new ArrayList<>();
    final Object logLock = new Object();
    final ArrayList<AppInfo> allApps = new ArrayList<>();
    volatile boolean rootGranted = false;
    volatile boolean rootChecked = false;
    volatile boolean serviceRunning = false;
    volatile boolean hasPendingRestore = false;
    volatile boolean serviceRedirectMode = false;
    Process serviceProc = null;
    Thread logTailThread = null;
    volatile long logTailOffset = 0;
    final StringBuilder logBuffer = new StringBuilder();
    Process termProc = null;
    BufferedWriter termIn = null;
    String pendingRunScript = null;
    String scriptPath = "/sdcard";
    Thread watcherThread = null;
    volatile boolean watcherRunning = false;

    public static class AppInfo {
        boolean checked;
        Drawable icon;
        String label;
        String pkg;
        boolean system;
        final List<String> paths = new ArrayList<>();

        AppInfo(Drawable drawable, String str, String str2, boolean z) {
            this.icon = drawable;
            this.label = str;
            this.pkg = str2;
            this.system = z;
        }
    }

    public static class FsEntry {
        boolean isDir;
        String name;
        long size;

        FsEntry(String str, boolean z) {
            this.name = str;
            this.isDir = z;
        }
    }

    public static class Res {
        int code;
        String out;

        Res(int i, String str) {
            this.code = i;
            this.out = str;
        }
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.cfgFile = new File(getFilesDir(), "config.txt");
        this.scriptPathPref = new File(getFilesDir(), "script_path.txt");
        this.logFile = new File(getFilesDir(), "logd.log");
        this.pathsLogFile = new File(getFilesDir(), "paths.log");
        this.hiddenAppsFile = new File(getFilesDir(), "hidden_apps.txt");
        loadScriptPath();
        buildUi();
        buildHome();
        loadPreviousLogs();
        appendLog("[系统] —— 会话开始 ——");
        scrollToLogBottom();
        ensureBinaryAsync();
        loadAppsAsync();
    }

    @Override
    public void onWindowFocusChanged(boolean z) {
        super.onWindowFocusChanged(z);
        if (!z || this.rootChecked) {
            return;
        }
        this.rootChecked = true;
        requestRoot();
    }

    int dp(int i) {
        return (int) ((i * getResources().getDisplayMetrics().density) + 0.5f);
    }

    GradientDrawable round(int i, float f) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(f);
        return gradientDrawable;
    }

    Drawable glassRipple() {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), getDrawable(R.drawable.bg_card), getDrawable(R.drawable.bg_card));
    }

    Drawable tint(Drawable drawable, int i) {
        Drawable mutate = drawable.mutate();
        mutate.setTint(i);
        return mutate;
    }

    Drawable icon(int i, int i2) {
        return tint(getDrawable(i), i2);
    }

    TextView tv(String str, int i, int i2, boolean z) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(i);
        textView.setTextColor(i2);
        if (z) {
            textView.setTypeface(Typeface.DEFAULT, 1);
        }
        return textView;
    }

    void buildUi() {
        FrameLayout frameLayout = new FrameLayout(this);
        this.root = frameLayout;
        frameLayout.setBackground(getDrawable(R.drawable.bg_glass));
        applyInsets(this.root);

        TextView textView = new TextView(this);
        this.floatToast = textView;
        textView.setTextColor(TEXT);
        this.floatToast.setTextSize(13.0f);
        this.floatToast.setTypeface(Typeface.DEFAULT, 1);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(-285212673);
        gradientDrawable.setCornerRadius(dp(999));
        gradientDrawable.setStroke(dp(1), -2130706433);
        this.floatToast.setBackground(gradientDrawable);
        this.floatToast.setElevation(dp(3));
        this.floatToast.setPadding(dp(20), dp(11), dp(20), dp(11));
        this.floatToast.setVisibility(8);
        this.floatToast.setAlpha(0.0f);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(-2, -2, 81);
        layoutParams.bottomMargin = dp(96);
        this.root.addView(this.floatToast, layoutParams);

        this.homeScroll = new ScrollView(this) {
            @Override
            public void fling(int i) {
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent motionEvent) {
                return false;
            }

            @Override
            public boolean onTouchEvent(MotionEvent motionEvent) {
                return false;
            }

            @Override
            public void scrollTo(int i, int i2) {
                super.scrollTo(0, 0);
            }

            @Override
            public void setScrollY(int i) {
                super.setScrollY(0);
            }
        };

        LinearLayout linearLayout = new LinearLayout(this);
        this.homeContent = linearLayout;
        linearLayout.setOrientation(1);
        this.homeContent.setPadding(dp(20), dp(32), dp(20), dp(20));
        this.homeScroll.addView(this.homeContent);
        this.root.addView(this.homeScroll);

        LinearLayout linearLayout2 = new LinearLayout(this);
        this.pickerContent = linearLayout2;
        linearLayout2.setOrientation(1);
        buildPicker();
        this.root.addView(this.pickerContent, new FrameLayout.LayoutParams(-1, -1));
        this.pickerContent.setVisibility(8);

        LinearLayout linearLayout3 = new LinearLayout(this);
        this.scriptContent = linearLayout3;
        linearLayout3.setOrientation(1);
        buildScripts();
        this.root.addView(this.scriptContent, new FrameLayout.LayoutParams(-1, -1));
        this.scriptContent.setVisibility(8);

        LinearLayout linearLayout4 = new LinearLayout(this);
        this.terminalContent = linearLayout4;
        linearLayout4.setOrientation(1);
        buildTerminal();
        this.root.addView(this.terminalContent, new FrameLayout.LayoutParams(-1, -1));
        this.terminalContent.setVisibility(8);

        setContentView(this.root);
    }

    void applyInsets(final View view) {
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view2, WindowInsets windowInsets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                    top = insets.top;
                    bottom = insets.bottom;
                } else {
                    top = windowInsets.getSystemWindowInsetTop();
                    bottom = windowInsets.getSystemWindowInsetBottom();
                }
                view.setPadding(0, top, 0, bottom);
                return windowInsets;
            }
        });
    }

    void buildHome() {
        this.homeContent.removeAllViews();

        View view = new View(this);
        this.statusDot = view;
        view.setBackground(round(OFF, dp(8)));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        layoutParams.setMargins(dp(4), 0, dp(12), 0);

        this.statusText = tv("服务未运行", 18, TEXT, true);
        this.statusSub = tv("检测中…", 12, SUBTEXT, false);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.addView(this.statusText);
        linearLayout.addView(this.statusSub);

        LinearLayout glassCard = glassCard();
        glassCard.setGravity(16);
        glassCard.setOrientation(0);
        glassCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, dp(12), 0, 0);
        this.homeContent.addView(glassCard, layoutParams2);
        glassCard.addView(this.statusDot, layoutParams);
        glassCard.addView(linearLayout, new LinearLayout.LayoutParams(0, -2, 1.0f));
        glassCard.setOnClickListener(v -> {
            if (!rootGranted) {
                requestRoot();
            }
        });
        spring(glassCard);

        // Menu cards
        TextView textView = new TextView(this);
        this.hideListPill = textView;
        this.homeContent.addView(menuCard("隐藏应用", "临时卸载应用，保留数据仅本应用可访问", 0, textView, -7640088, -988673, R.drawable.ic_snow));

        TextView textView3 = new TextView(this);
        this.scriptPill = textView3;
        this.homeContent.addView(menuCard("脚本管理", "轻量终端 · 系统环境执行脚本", 1, textView3, -13654417, -1706260, R.drawable.ic_terminal));

        TextView textView4 = new TextView(this);
        this.hideDirPill = textView4;
        this.homeContent.addView(menuCard("隐藏路径", "保护敏感目录和文件，仅本应用可访问", 2, textView4, -1545365, -4627, R.drawable.ic_hide));

        // Toggle button (single, below menu cards)
        Button toggle = new Button(this);
        this.toggleBtn = toggle;
        toggle.setAllCaps(false);
        toggle.setTypeface(Typeface.DEFAULT, 1);
        toggle.setTextSize(15.0f);
        toggle.setElevation(dp(3));
        toggle.setGravity(17);
        toggle.setTextColor(-1);
        toggle.setBackground(round(DANGER, dp(24)));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-1, dp(48));
        toggleLp.setMargins(0, dp(16), 0, 0);
        toggle.setLayoutParams(toggleLp);
        toggle.setOnClickListener(v -> {
            if (serviceRunning) {
                restoreApps();
            } else {
                hideApps();
            }
        });
        spring(toggle);
        this.homeContent.addView(toggle);

        // Log area
        LinearLayout glassCard2 = glassCard();
        LinearLayout.LayoutParams layoutParams4 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams4.setMargins(0, dp(20), 0, 0);
        glassCard2.setPadding(dp(12), dp(10), dp(12), dp(10));
        this.homeContent.addView(glassCard2, layoutParams4);

        LinearLayout linearLayout6 = new LinearLayout(this);
        linearLayout6.setOrientation(0);
        linearLayout6.setGravity(16);
        linearLayout6.addView(tv("运行日志", 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button mkSmallBtn = mkSmallBtn("导出", OK);
        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(-2, dp(30));
        layoutParams5.setMargins(0, 0, dp(8), 0);
        mkSmallBtn.setLayoutParams(layoutParams5);
        linearLayout6.addView(mkSmallBtn);

        Button mkSmallBtn2 = mkSmallBtn("清空", PRIMARY);
        linearLayout6.addView(mkSmallBtn2);
        glassCard2.addView(linearLayout6);

        spring(mkSmallBtn);
        mkSmallBtn.setOnClickListener(v -> exportLog());
        spring(mkSmallBtn2);
        mkSmallBtn2.setOnClickListener(v -> clearLog());

        ScrollView scrollView = new ScrollView(this);
        this.logScroll = scrollView;
        scrollView.setVerticalScrollBarEnabled(true);
        TextView textView5 = new TextView(this);
        this.logView = textView5;
        textView5.setTextSize(12.0f);
        this.logView.setTextColor(SUBTEXT);
        this.logView.setTypeface(Typeface.MONOSPACE);
        this.logView.setTextIsSelectable(true);
        this.logView.setPadding(dp(2), dp(8), dp(2), dp(4));
        this.logScroll.addView(this.logView);
        LinearLayout.LayoutParams layoutParams6 = new LinearLayout.LayoutParams(-1, dp(180));
        layoutParams6.setMargins(0, dp(4), 0, 0);
        glassCard2.addView(this.logScroll, layoutParams6);
        this.logScroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == 0) {
                homeScroll.requestDisallowInterceptTouchEvent(true);
            } else if (action == 1 || action == 3) {
                homeScroll.requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        // Footer links
        LinearLayout linearLayout7 = new LinearLayout(this);
        linearLayout7.setOrientation(0);
        linearLayout7.setGravity(17);
        linearLayout7.setPadding(0, dp(8), 0, dp(4));
        this.homeContent.addView(linearLayout7);

        TextView textView6 = new TextView(this);
        textView6.setText("说明文档");
        textView6.setTextSize(12.0f);
        textView6.setTextColor(PRIMARY);
        textView6.setTypeface(Typeface.DEFAULT, 1);
        textView6.setPaintFlags(textView6.getPaintFlags() | 8);
        textView6.setPadding(dp(10), dp(6), dp(10), dp(6));
        textView6.setOnClickListener(v -> openDocs());
        LinearLayout.LayoutParams layoutParams7 = new LinearLayout.LayoutParams(-2, -2);
        layoutParams7.setMargins(0, 0, dp(20), 0);
        linearLayout7.addView(textView6, layoutParams7);

        TextView textView7 = new TextView(this);
        textView7.setText("Bug 反馈");
        textView7.setTextSize(12.0f);
        textView7.setTextColor(PRIMARY);
        textView7.setGravity(17);
        textView7.setTypeface(Typeface.DEFAULT, 1);
        textView7.setPaintFlags(textView7.getPaintFlags() | 8);
        textView7.setPadding(dp(10), dp(6), dp(10), dp(6));
        textView7.setOnClickListener(v -> openBugReport());
        linearLayout7.addView(textView7);

        // UI 最底部小字说明
        TextView note = tv("误操作导致的系统异常，守护进程不小心崩溃导致的应用丢失，重启后重新打开本应用即可恢复原状", 11, SUBTEXT, false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.setMargins(0, dp(12), 0, dp(8));
        note.setLayoutParams(noteLp);
        note.setLineSpacing((float) dp(2), 1.0f);
        note.setGravity(17);
        this.homeContent.addView(note);

        updateStatus();
    }

    LinearLayout glassCard() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackground(glassRipple());
        linearLayout.setElevation(dp(1));
        return linearLayout;
    }

    Button mkSmallBtn(String str, int i) {
        Button button = new Button(this);
        button.setText(str);
        button.setTextColor(i);
        button.setTextSize(13.0f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setBackground(round(PRIMARY_SOFT, dp(12)));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(34)));
        return button;
    }

    View menuCard(String str, String str2, final int i, TextView textView, int i2, int i3, int i4) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(14), dp(10), dp(14), dp(10));
        linearLayout.setBackground(glassRipple());
        linearLayout.setElevation(dp(1));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, dp(8), 0, 0);
        linearLayout.setLayoutParams(layoutParams);

        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(icon(i4, i2));
        imageView.setBackground(round(i3, dp(12)));
        imageView.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(dp(38), dp(38));
        layoutParams2.setMargins(0, 0, dp(12), 0);
        linearLayout.addView(imageView, layoutParams2);

        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.addView(tv(str, 15, TEXT, true));
        linearLayout2.addView(tv(str2, 12, SUBTEXT, false));
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(0, -2, 1.0f));

        textView.setTextColor(PRIMARY);
        textView.setTextSize(11.0f);
        textView.setTypeface(Typeface.DEFAULT, 1);
        textView.setBackground(round(PRIMARY_SOFT, dp(10)));
        textView.setPadding(dp(8), dp(4), dp(8), dp(4));
        linearLayout.addView(textView);

        TextView tv = tv("›", 20, SUBTEXT, false);
        tv.setPadding(dp(8), 0, 0, 0);
        linearLayout.addView(tv);

        linearLayout.setOnClickListener(v -> {
            if (i == 1) {
                showScripts();
            } else if (i == 2) {
                showHideDirs();
            } else {
                showPicker();
            }
        });
        spring(linearLayout);
        return linearLayout;
    }

    void buildPicker() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(6), dp(6), dp(16), dp(6));
        linearLayout.setBackground(getDrawable(R.drawable.bg_topbar));

        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(icon(R.drawable.ic_back, TEXT));
        imageView.setPadding(dp(12), dp(8), dp(14), dp(8));
        linearLayout.addView(imageView);

        TextView tv = tv("隐藏应用", 20, TEXT, true);
        this.pickerTitle = tv;
        linearLayout.addView(tv);
        this.pickerContent.addView(linearLayout);

        imageView.setOnClickListener(v -> showHome());

        this.pickerScroll = new ScrollView(this);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(dp(16), dp(14), dp(16), dp(32));
        this.pickerScroll.addView(linearLayout2);
        this.pickerContent.addView(this.pickerScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        EditText editText = new EditText(this);
        this.search = editText;
        editText.setHint("搜索应用名称或包名");
        this.search.setTextSize(14.0f);
        this.search.setHintTextColor(SUBTEXT);
        this.search.setTextColor(TEXT);
        this.search.setSingleLine(true);
        this.search.setBackground(getDrawable(R.drawable.bg_search));
        this.search.setPadding(dp(20), dp(12), dp(20), dp(12));
        this.search.setCompoundDrawablesWithIntrinsicBounds(icon(R.drawable.ic_search, SUBTEXT), null, null, null);
        this.search.setCompoundDrawablePadding(dp(10));
        this.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
            }

            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
                rebuildList();
            }
        });
        linearLayout2.addView(this.search);

        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(0);
        linearLayout3.setGravity(16);
        linearLayout3.setPadding(dp(2), dp(14), dp(2), dp(8));
        linearLayout3.addView(tv("显示系统应用", 15, TEXT, false), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch r2 = new Switch(this);
        this.sysSwitch = r2;
        r2.setChecked(false);
        this.sysSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> rebuildList());
        linearLayout3.addView(this.sysSwitch);
        linearLayout2.addView(linearLayout3);

        LinearLayout glassCard = glassCard();
        glassCard.setPadding(0, dp(6), 0, dp(6));
        LinearLayout linearLayout4 = new LinearLayout(this);
        this.listBox = linearLayout4;
        linearLayout4.setOrientation(1);
        glassCard.addView(this.listBox);
        linearLayout2.addView(glassCard);
    }

    void buildHideDirs() {
        LinearLayout linearLayout = new LinearLayout(this);
        this.hideDirContent = linearLayout;
        linearLayout.setOrientation(1);
        this.hideDirContent.setVisibility(8);

        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.setPadding(dp(6), dp(6), dp(16), dp(6));
        linearLayout2.setBackground(getDrawable(R.drawable.bg_topbar));
        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(icon(R.drawable.ic_back, TEXT));
        imageView.setPadding(dp(12), dp(8), dp(14), dp(8));
        linearLayout2.addView(imageView);
        imageView.setOnClickListener(v -> showHome());
        linearLayout2.addView(tv("隐藏路径", 20, TEXT, true));
        this.hideDirContent.addView(linearLayout2);

        this.hideDirScroll = new ScrollView(this);
        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(1);
        linearLayout3.setPadding(dp(16), dp(14), dp(16), dp(32));
        this.hideDirScroll.addView(linearLayout3);
        this.hideDirContent.addView(this.hideDirScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout glassCard = glassCard();
        glassCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        View tv = tv("工作原理", 15, TEXT, true);
        TextView tv2 = tv("使用 Mount Namespace 隔离，指定目录或文件对系统其他进程完全不可见（目录显示为空，文件内容清空），仅 logd 守护进程可正常访问。需 root 权限且服务运行时生效。", 13, SUBTEXT, false);
        tv2.setLineSpacing(dp(4), 1.0f);
        glassCard.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
        layoutParams.setMargins(0, dp(6), 0, 0);
        glassCard.addView(tv2, layoutParams);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, 0, 0, dp(14));
        linearLayout3.addView(glassCard, layoutParams2);

        LinearLayout linearLayout4 = new LinearLayout(this);
        linearLayout4.setOrientation(0);
        linearLayout4.setGravity(16);
        final EditText editText = new EditText(this);
        editText.setHint("输入路径，如 /sdcard/Secret 或 /mnt/vendor/persist");
        editText.setTextSize(14.0f);
        editText.setHintTextColor(SUBTEXT);
        editText.setTextColor(TEXT);
        editText.setSingleLine(true);
        editText.setBackground(getDrawable(R.drawable.bg_search));
        editText.setPadding(dp(16), dp(12), dp(16), dp(12));
        editText.setImeOptions(6);
        editText.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i != 6) {
                return false;
            }
            addHideDir(editText.getText().toString().trim());
            editText.setText("");
            hideKeyboard(editText);
            return true;
        });
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        layoutParams3.setMargins(0, 0, dp(8), 0);
        linearLayout4.addView(editText, layoutParams3);

        Button browseBtn = new Button(this);
        browseBtn.setText("浏览");
        browseBtn.setTextColor(-1);
        browseBtn.setTextSize(13.0f);
        browseBtn.setAllCaps(false);
        browseBtn.setTypeface(Typeface.DEFAULT, 1);
        browseBtn.setBackground(round(PRIMARY, dp(12)));
        browseBtn.setElevation(dp(1));
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(dp(64), dp(46));
        browseLp.setMargins(0, 0, dp(8), 0);
        browseBtn.setLayoutParams(browseLp);
        browseBtn.setOnClickListener(v -> showDirPicker(editText.getText().toString().trim(), editText));
        linearLayout4.addView(browseBtn);
        spring(browseBtn);

        Button button2 = new Button(this);
        button2.setText("添加");
        button2.setTextColor(-1);
        button2.setTextSize(14.0f);
        button2.setAllCaps(false);
        button2.setTypeface(Typeface.DEFAULT, 1);
        button2.setBackground(round(-1545365, dp(12)));
        button2.setElevation(dp(1));
        button2.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(46)));
        button2.setOnClickListener(v -> {
            addHideDir(editText.getText().toString().trim());
            editText.setText("");
            hideKeyboard(editText);
        });
        linearLayout4.addView(button2);
        spring(button2);

        LinearLayout.LayoutParams layoutParams5 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams5.setMargins(0, 0, 0, dp(14));
        linearLayout3.addView(linearLayout4, layoutParams5);

        LinearLayout glassCard2 = glassCard();
        glassCard2.setPadding(0, dp(6), 0, dp(6));
        LinearLayout linearLayout5 = new LinearLayout(this);
        this.hideDirList = linearLayout5;
        linearLayout5.setOrientation(1);
        glassCard2.addView(this.hideDirList);
        linearLayout3.addView(glassCard2);

        this.root.addView(this.hideDirContent, new FrameLayout.LayoutParams(-1, -1));
    }

    void showHome() {
        this.pickerContent.setVisibility(8);
        this.scriptContent.setVisibility(8);
        this.terminalContent.setVisibility(8);
        LinearLayout linearLayout = this.hideDirContent;
        if (linearLayout != null) {
            linearLayout.setVisibility(8);
        }
        this.homeScroll.setVisibility(0);
        this.homeScroll.scrollTo(0, 0);
        updateHomeCounts();
        updateStatus();
    }

    void showPicker() {
        this.pickerTitle.setText("隐藏应用");
        this.homeScroll.setVisibility(8);
        this.scriptContent.setVisibility(8);
        this.terminalContent.setVisibility(8);
        LinearLayout linearLayout = this.hideDirContent;
        if (linearLayout != null) {
            linearLayout.setVisibility(8);
        }
        this.pickerContent.setVisibility(0);
        this.search.setText("");
        // 先用旧列表显示
        rebuildList();
        // 后台重新加载应用列表 (会刷新已卸载/新安装的应用)
        loadAppsAsync();
    }

    void showScripts() {
        this.homeScroll.setVisibility(8);
        this.pickerContent.setVisibility(8);
        this.terminalContent.setVisibility(8);
        LinearLayout linearLayout = this.hideDirContent;
        if (linearLayout != null) {
            linearLayout.setVisibility(8);
        }
        this.scriptContent.setVisibility(0);
        refreshFileList();
    }

    void showHideDirs() {
        if (this.hideDirContent == null) {
            buildHideDirs();
        }
        this.homeScroll.setVisibility(8);
        this.pickerContent.setVisibility(8);
        this.scriptContent.setVisibility(8);
        this.terminalContent.setVisibility(8);
        this.hideDirContent.setVisibility(0);
        refreshHideDirList();
    }

    void addHideDir(String str) {
        if (str == null || str.isEmpty()) {
            showFloat("路径不能为空");
            return;
        }
        if (!str.startsWith("/")) {
            showFloat("请输入绝对路径（以 / 开头）");
            return;
        }
        for (String s : this.hideDirs) {
            if (s.equals(str)) {
                showFloat("该目录已存在");
                return;
            }
        }
        this.hideDirs.add(str);
        writeConfig();
        refreshHideDirList();
        updateHomeCounts();
        showFloat("已添加：" + str);
        appendLog("[隐藏路径] 已添加隐藏路径：" + str);
    }

    void showDirPicker(String str, EditText editText) {
        this.pickerInput = editText;
        String str2 = (str == null || !str.startsWith("/")) ? "/" : str;

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(14), dp(10), dp(14), dp(4));

        final EditText editText2 = new EditText(this);
        editText2.setText(str2);
        editText2.setTextSize(13.0f);
        editText2.setTextColor(TEXT);
        editText2.setHintTextColor(SUBTEXT);
        editText2.setSingleLine(true);
        editText2.setTypeface(Typeface.MONOSPACE);
        editText2.setBackground(getDrawable(R.drawable.bg_search));
        editText2.setPadding(dp(12), dp(8), dp(12), dp(8));
        linearLayout.addView(editText2, new LinearLayout.LayoutParams(-1, -2));

        final TextView textView = new TextView(this);
        textView.setText("加载中…");
        textView.setTextSize(11.0f);
        textView.setTextColor(SUBTEXT);
        textView.setPadding(dp(4), dp(6), dp(4), dp(2));
        linearLayout.addView(textView);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackground(round(-657926, dp(10)));
        final LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(dp(4), dp(2), dp(4), dp(2));
        scrollView.addView(linearLayout2);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        layoutParams.setMargins(0, dp(2), 0, dp(6));
        linearLayout.addView(scrollView, layoutParams);

        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(0);
        linearLayout3.setPadding(0, dp(2), 0, dp(4));
        String[] strArr = {"/", "/data", "/sdcard", "/system", "/vendor", "/mnt"};
        for (final String str3 : strArr) {
            Button button = new Button(this);
            button.setText(str3);
            button.setTextColor(PRIMARY);
            button.setTextSize(11.0f);
            button.setAllCaps(false);
            button.setBackground(round(0, dp(8)));
            button.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-2, -2);
            layoutParams2.setMargins(0, 0, dp(6), 0);
            button.setLayoutParams(layoutParams2);
            button.setOnClickListener(v -> {
                editText2.setText(str3);
                listDirsInPicker(str3, editText2, linearLayout2, textView);
            });
            linearLayout3.addView(button);
        }
        linearLayout.addView(linearLayout3);

        Button button2 = new Button(this);
        button2.setText("⬆  返回上级");
        button2.setTextColor(PRIMARY);
        button2.setTextSize(13.0f);
        button2.setAllCaps(false);
        button2.setBackground(round(442207727, dp(10)));
        button2.setOnClickListener(v -> {
            String trim = editText2.getText().toString().trim();
            if (trim.equals("/")) {
                return;
            }
            while (trim.length() > 1 && trim.endsWith("/")) {
                trim = trim.substring(0, trim.length() - 1);
            }
            String parent = "/";
            int lastIndexOf = trim.lastIndexOf(47);
            if (lastIndexOf > 0) {
                parent = trim.substring(0, lastIndexOf);
            }
            editText2.setText(parent);
            listDirsInPicker(parent, editText2, linearLayout2, textView);
        });
        LinearLayout.LayoutParams layoutParams3 = new LinearLayout.LayoutParams(-1, dp(36));
        layoutParams3.setMargins(0, dp(2), 0, dp(4));
        linearLayout.addView(button2, layoutParams3);

        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout container = dialogContainer("选择文件或目录");
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
        pcLp.setMargins(0, dp(10), 0, 0);
        container.addView(linearLayout, pcLp);
        LinearLayout btnRow = dialogButtonRow("选择此路径", "取消", holder);
        // Override positive button: validate path before dismiss
        Button posBtn = (Button) btnRow.getChildAt(btnRow.getChildCount() - 1);
        posBtn.setOnClickListener(v -> {
            String trim = editText2.getText().toString().trim();
            if (!trim.startsWith("/")) {
                showFloat("请输入绝对路径");
                return;
            }
            EditText pickerInput = MainActivity.this.pickerInput;
            if (pickerInput != null) {
                pickerInput.setText(trim);
                MainActivity.this.pickerInput.setSelection(trim.length());
            }
            if (holder[0] != null) holder[0].dismiss();
        });
        container.addView(btnRow);
        final AlertDialog create = showDialog(container, true);
        holder[0] = create;
        this.pickerDialog = create;
        Window window = create.getWindow();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
            window.setAttributes(attributes);
        }
        editText2.setOnEditorActionListener((textView2, i, keyEvent) -> {
            if (i != 6 && i != 2) {
                return false;
            }
            String trim = editText2.getText().toString().trim();
            if (trim.startsWith("/")) {
                listDirsInPicker(trim, editText2, linearLayout2, textView);
            }
            hideKeyboard(editText2);
            return true;
        });
        listDirsInPicker(str2, editText2, linearLayout2, textView);
    }

    void listDirsInPicker(final String str, final EditText editText, final LinearLayout linearLayout, final TextView textView) {
        linearLayout.removeAllViews();
        textView.setText("加载中…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            StringBuilder sb2 = new StringBuilder();
            try {
                Process exec = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "for f in \"" + str.replace("\"", "\\\"") + "\"/*; do [ -e \"$f\" ] || continue; name=$(basename \"$f\"); if [ -d \"$f\" ]; then echo \"D:$name\"; elif [ -f \"$f\" ]; then echo \"F:$name\"; fi; done 2>/dev/null"});
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(exec.getInputStream()));
                while (true) {
                    String readLine = bufferedReader.readLine();
                    if (readLine == null) {
                        break;
                    }
                    String trim = readLine.trim();
                    if (!trim.isEmpty()) {
                        if (trim.startsWith("D:")) {
                            sb.append(trim.substring(2));
                            sb.append("\n");
                        } else if (trim.startsWith("F:")) {
                            sb2.append(trim.substring(2));
                            sb2.append("\n");
                        }
                    }
                }
                bufferedReader.close();
                exec.waitFor();
            } catch (Exception e) {
                sb.setLength(0);
                sb2.setLength(0);
                sb.append("ERROR:");
                sb.append(e.getMessage());
            }
            final String dirs = sb.toString();
            final String files = sb2.toString();
            runOnUiThread(() -> populatePickerList(linearLayout, dirs, textView, files, str, editText));
        }).start();
    }

    void populatePickerList(LinearLayout linearLayout, String str, TextView textView, String str2, String str3, EditText editText) {
        linearLayout.removeAllViews();
        if (str.startsWith("ERROR:")) {
            textView.setText("读取失败：" + str.substring(6));
            return;
        }
        String[] dirs = str.trim().isEmpty() ? new String[0] : str.split("\n");
        String[] files = str2.trim().isEmpty() ? new String[0] : str2.split("\n");
        Arrays.sort(dirs);
        Arrays.sort(files);

        for (String name : dirs) {
            if (name.isEmpty()) continue;
            final String full = (str3.equals("/") ? "/" : str3 + "/") + name;
            final TextView tv = new TextView(this);
            tv.setText("📁  " + name);
            tv.setTextColor(-15066578);
            tv.setTextSize(12.0f);
            tv.setPadding(dp(10), dp(7), dp(10), dp(7));
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setOnClickListener(v -> {
                editText.setText(full);
                listDirsInPicker(full, editText, linearLayout, textView);
            });
            tv.setOnLongClickListener(v -> {
                EditText pickerInput = this.pickerInput;
                if (pickerInput != null) {
                    pickerInput.setText(full);
                    this.pickerInput.setSelection(full.length());
                }
                showFloat("已选择：" + full);
                return true;
            });
            linearLayout.addView(tv);
            View divider = new View(this);
            divider.setBackgroundColor(436207616);
            linearLayout.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }

        for (String name : files) {
            if (name.isEmpty()) continue;
            final String full = (str3.equals("/") ? "/" : str3 + "/") + name;
            TextView tv = new TextView(this);
            tv.setText("📄  " + name);
            tv.setTextColor(-11908502);
            tv.setTextSize(12.0f);
            tv.setPadding(dp(10), dp(7), dp(10), dp(7));
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setOnClickListener(v -> {
                EditText pickerInput = this.pickerInput;
                if (pickerInput != null) {
                    pickerInput.setText(full);
                    this.pickerInput.setSelection(full.length());
                }
                showFloat("已选择文件：" + full);
                AlertDialog dialog = this.pickerDialog;
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            });
            linearLayout.addView(tv);
            View divider = new View(this);
            divider.setBackgroundColor(436207616);
            linearLayout.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }

        int dirCount = dirs.length == 1 && dirs[0].isEmpty() ? 0 : dirs.length;
        int fileCount = files.length == 1 && files[0].isEmpty() ? 0 : files.length;
        textView.setText(str3 + "  —  " + dirCount + " 个目录，" + fileCount + " 个文件");
    }

    void removeHideDir(final String str) {
        showCustomDialog("确认移除", "确定要从隐藏路径中移除该路径吗？\n\n" + str,
                null, "移除", "取消", true,
                () -> {
                    this.hideDirs.remove(str);
                    writeConfig();
                    refreshHideDirList();
                    updateHomeCounts();
                    showFloat("已移除");
                    appendLog("[隐藏路径] 已移除隐藏路径：" + str);
                }, null);
    }

    void refreshHideDirList() {
        LinearLayout linearLayout = this.hideDirList;
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        if (this.hideDirs.isEmpty()) {
            TextView tv = tv("暂无隐藏路径\n请在上方输入路径后点击添加", 14, SUBTEXT, false);
            tv.setGravity(17);
            tv.setPadding(0, dp(36), 0, dp(36));
            tv.setLineSpacing(dp(4), 1.0f);
            this.hideDirList.addView(tv);
            return;
        }
        for (int i = 0; i < this.hideDirs.size(); i++) {
            final String str = this.hideDirs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));

            ImageView imageView = new ImageView(this);
            imageView.setImageDrawable(icon(new File(str).isDirectory() ? R.drawable.ic_folder : R.drawable.ic_file, -1545365));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMargins(0, 0, dp(10), 0);
            row.addView(imageView, lp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(1);
            String name = new File(str).getName();
            info.addView(tv(name.isEmpty() ? str : name, 14, TEXT, true));
            TextView path = tv(str, 11, SUBTEXT, false);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            info.addView(path);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

            ImageView del = new ImageView(this);
            del.setImageDrawable(icon(android.R.drawable.ic_menu_delete, DANGER));
            del.setPadding(dp(6), dp(6), dp(6), dp(6));
            del.setOnClickListener(v -> removeHideDir(str));
            row.addView(del);

            this.hideDirList.addView(row);
            spring(row);
        }
    }

    void openTerminal(String str) {
        this.homeScroll.setVisibility(8);
        this.pickerContent.setVisibility(8);
        this.scriptContent.setVisibility(8);
        this.terminalContent.setVisibility(0);
        if (str != null) {
            if (this.termProc != null) {
                termWrite(termScriptCmd(str));
            } else {
                this.pendingRunScript = str;
                termStart();
            }
        } else {
            termStart();
        }
        this.termInput.postDelayed(() -> {
            this.termInput.requestFocus();
            showKeyboard(this.termInput);
        }, 300L);
    }

    String termScriptCmd(String str) {
        String shq = shq(str);
        String shq2 = shq(new File(str).getParent() != null ? new File(str).getParent() : "/");
        return "if [ \"$(head -c4 " + shq + " 2>/dev/null | od -An -tx1 | tr -d ' \\n')\" = \"7f454c46\" ]; then cd " + shq2 + " && chmod +x " + shq + " && export LD_LIBRARY_PATH=" + shq2 + ":\\\"$LD_LIBRARY_PATH\\\" && exec " + shq + "; else cd " + shq2 + " && sh " + shq + "; fi";
    }

    @Override
    public void onBackPressed() {
        if (this.scriptContent.getVisibility() == 0) {
            showHome();
            return;
        }
        if (this.terminalContent.getVisibility() == 0) {
            showHome();
            return;
        }
        if (this.pickerContent.getVisibility() == 0) {
            showHome();
            return;
        }
        LinearLayout linearLayout = this.hideDirContent;
        if (linearLayout == null || linearLayout.getVisibility() != 0) {
            super.onBackPressed();
        } else {
            showHome();
        }
    }

    void loadAppsAsync() {
        // 保存当前勾选状态
        HashSet<String> prevChecked = new HashSet<>();
        for (AppInfo info : this.allApps) {
            if (info.checked) {
                prevChecked.add(info.pkg);
            }
        }
        new Thread(() -> {
            try {
                final PackageManager pm = getPackageManager();
                ArrayList<ApplicationInfo> list = new ArrayList<>(pm.getInstalledApplications(0));
                list.sort(Comparator.comparing(o -> pm.getApplicationLabel(o).toString()));
                // 清空旧列表
                final ArrayList<AppInfo> newList = new ArrayList<>();
                for (ApplicationInfo ai : list) {
                    String label = pm.getApplicationLabel(ai).toString();
                    Drawable icon = ai.loadIcon(pm);
                    boolean isSystem = (ai.flags & 1) != 0;
                    AppInfo info = new AppInfo(icon, label, ai.packageName, isSystem);
                    newList.add(info);
                }
                runOnUiThread(() -> {
                    // 替换旧列表
                    this.allApps.clear();
                    this.allApps.addAll(newList);
                    applyConfigFromFile();
                    loadHiddenApps();
                    // 恢复之前的勾选状态
                    for (AppInfo info : this.allApps) {
                        if (prevChecked.contains(info.pkg)) {
                            info.checked = true;
                        }
                    }
                    updateHomeCounts();
                    updateStatus();
                    if (this.pickerContent.getVisibility() == 0) {
                        rebuildList();
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> appendLog("[错误] 加载应用列表失败：" + e.getMessage()));
            }
        }).start();
    }

    void applyConfigFromFile() {
        try {
            if (this.cfgFile.exists()) {
                ArrayList<String> customList = new ArrayList<>();
                BufferedReader reader = new BufferedReader(new FileReader(this.cfgFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trim = line.trim();
                    if (trim.startsWith("custom:")) {
                        customList.add(trim.substring(7).trim());
                    }
                }
                reader.close();
                this.hideDirs.clear();
                this.hideDirs.addAll(customList);
            }
        } catch (Exception ignored) {
        }
    }

    void loadHiddenApps() {
        try {
            if (this.hiddenAppsFile.exists()) {
                ArrayList<String> hidden = new ArrayList<>();
                BufferedReader reader = new BufferedReader(new FileReader(this.hiddenAppsFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trim = line.trim();
                    if (!trim.isEmpty()) {
                        hidden.add(trim);
                    }
                }
                reader.close();
                for (AppInfo info : this.allApps) {
                    if (hidden.contains(info.pkg)) {
                        info.checked = true;
                        // 为已勾选的应用重新检测路径（列表刷新后 paths 为空）
                        if (info.paths.isEmpty()) {
                            detectPathsSync(info);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // 同步版本路径检测（不启动新线程，在调用线程内执行）
    void detectPathsSync(final AppInfo info) {
        info.paths.clear();
        String pkg = info.pkg;

        // 1. /data/data/<pkg>
        String privData = "/data/data/" + pkg;
        info.paths.add(privData);

        // 2. /storage/emulated/0/Android/data/<pkg>
        String fuseData = "/storage/emulated/0/Android/data/" + pkg;
        if (pathExists(fuseData)) {
            info.paths.add(fuseData);
        }

        // 3. /storage/emulated/0/Android/obb/<pkg>
        String fuseObb = "/storage/emulated/0/Android/obb/" + pkg;
        if (pathExists(fuseObb)) {
            info.paths.add(fuseObb);
        }
    }

    void rebuildList() {
        this.listBox.removeAllViews();
        boolean showSystem = this.sysSwitch.isChecked();
        String query = this.search.getText().toString().trim().toLowerCase();
        boolean hasQuery = query.length() > 0;
        ArrayList<AppInfo> filtered = new ArrayList<>();
        for (AppInfo info : this.allApps) {
            if (showSystem || !info.system) {
                if (!hasQuery || info.label.toLowerCase().contains(query) || info.pkg.toLowerCase().contains(query)) {
                    filtered.add(info);
                }
            }
        }
        filtered.sort((a, b) -> {
            if (a.checked != b.checked) {
                return a.checked ? -1 : 1;
            }
            return 0;
        });
        for (AppInfo info : filtered) {
            this.listBox.addView(appRow(info));
        }
        if (filtered.isEmpty()) {
            TextView tv = tv(hasQuery ? "没有匹配的应用" : "未找到用户应用", 14, SUBTEXT, false);
            tv.setGravity(17);
            tv.setPadding(0, dp(36), 0, dp(36));
            this.listBox.addView(tv);
        }
    }

    View appRow(final AppInfo appInfo) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(4), dp(7), dp(4), dp(7));
        linearLayout.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));

        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(appInfo.icon);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(0, 0, dp(12), 0);
        linearLayout.addView(imageView, lp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(1);
        info.addView(tv(appInfo.label, 15, TEXT, true));
        info.addView(tv(appInfo.pkg, 12, SUBTEXT, false));
        linearLayout.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

        final CheckBox checkBox = new CheckBox(this);
        checkBox.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        checkBox.setChecked(appInfo.checked);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appInfo.checked = isChecked;
            if (isChecked) {
                detectPaths(appInfo);
            } else {
                appInfo.paths.clear();
            }
            updateHomeCounts();
            writeConfig();
            String action = isChecked ? "勾选" : "取消";
            appendLog("[配置] " + action + "：" + appInfo.label + " (" + appInfo.pkg + ")");
            showFloat(appInfo.label + (isChecked ? " 已选择" : " 已取消") + "\n" + appInfo.pkg);
            this.listBox.post(this::rebuildList);
        });
        linearLayout.addView(checkBox);

        linearLayout.setOnClickListener(v -> checkBox.toggle());
        spring(linearLayout);
        return linearLayout;
    }

    void detectPaths(final AppInfo info) {
        new Thread(() -> {
            info.paths.clear();
            String pkg = info.pkg;

            // 1. /data/data/<pkg> (私有数据目录, SELinux 限制 su 无法检测, logd 可访问)
            //    无条件添加, logd 会自动跳过不存在的
            String privData = "/data/data/" + pkg;
            info.paths.add(privData);
            appendLog("[路径] 添加私有数据目录：" + privData);

            // 2. /storage/emulated/0/Android/data/<pkg> (FUSE 路径, logd 会自动处理媒体路径)
            String fuseData = "/storage/emulated/0/Android/data/" + pkg;
            if (pathExists(fuseData)) {
                info.paths.add(fuseData);
                appendLog("[路径] 检测到数据目录(FUSE)：" + fuseData);
            } else {
                appendLog("[路径] 数据目录不存在，跳过：" + fuseData);
            }

            // 3. /storage/emulated/0/Android/obb/<pkg> (FUSE OBB)
            String fuseObb = "/storage/emulated/0/Android/obb/" + pkg;
            if (pathExists(fuseObb)) {
                info.paths.add(fuseObb);
                appendLog("[路径] 检测到 OBB 目录(FUSE)：" + fuseObb);
            }

            writePathsLog();
            if (info.paths.isEmpty()) {
                appendLog("[路径] " + info.label + " 未检测到可隐藏路径");
            } else {
                appendLog("[路径] " + info.label + " 共检测到 " + info.paths.size() + " 个路径");
            }
        }).start();
    }

    boolean pathExists(String path) {
        try {
            Res res = suExec("test -e " + shq(path) + " && echo yes");
            return res.code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    void writePathsLog() {
        try {
            StringBuilder sb = new StringBuilder();
            for (AppInfo info : this.allApps) {
                if (info.checked && !info.paths.isEmpty()) {
                    sb.append("# ").append(info.label).append(" (").append(info.pkg).append(")\n");
                    for (String p : info.paths) {
                        sb.append(p).append('\n');
                    }
                    sb.append('\n');
                }
            }
            FileWriter fw = new FileWriter(this.pathsLogFile);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception e) {
            appendLog("[错误] 写入路径日志失败：" + e.getMessage());
        }
    }

    ArrayList<String> readHiddenApps() {
        ArrayList<String> list = new ArrayList<>();
        try {
            if (this.hiddenAppsFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(this.hiddenAppsFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    String trim = line.trim();
                    if (!trim.isEmpty()) {
                        list.add(trim);
                    }
                }
                reader.close();
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    void writeHiddenApps(List<String> packages) {
        try {
            FileWriter fw = new FileWriter(this.hiddenAppsFile);
            for (String pkg : packages) {
                fw.write(pkg + "\n");
            }
            fw.close();
        } catch (Exception e) {
            appendLog("[错误] 写入隐藏应用列表失败：" + e.getMessage());
        }
    }

    void clearHiddenApps() {
        try {
            if (this.hiddenAppsFile.exists()) {
                this.hiddenAppsFile.delete();
            }
        } catch (Exception ignored) {
        }
    }

    void updateHomeCounts() {
        int checkedCount = 0;
        for (AppInfo info : this.allApps) {
            if (info.checked) {
                checkedCount++;
            }
        }
        TextView hidePill = this.hideListPill;
        if (hidePill != null) {
            hidePill.setText("已选 " + checkedCount);
        }
        TextView scriptPill = this.scriptPill;
        if (scriptPill != null) {
            scriptPill.setText("打开");
        }
        TextView hideDirPill = this.hideDirPill;
        if (hideDirPill != null) {
            hideDirPill.setText("已隐藏 " + this.hideDirs.size());
        }
    }

    void updateStatus() {
        int checkedCount = 0;
        for (AppInfo info : this.allApps) {
            if (info.checked) {
                checkedCount++;
            }
        }
        this.statusText.setText(this.serviceRunning ? "服务运行中" : "服务未运行");
        this.statusDot.setBackground(round(this.serviceRunning ? OK : OFF, dp(8)));
        this.statusSub.setText((this.rootGranted ? "已获 root 权限" : "未获 root 权限（点击授权）") + "  ·  隐藏 " + checkedCount);
        updateToggleBtn();
    }

    void updateToggleBtn() {
        if (this.toggleBtn == null) return;
        if (this.serviceRunning) {
            this.toggleBtn.setText("恢复");
            this.toggleBtn.setBackground(round(OK, dp(24)));
        } else {
            this.toggleBtn.setText("隐藏");
            this.toggleBtn.setBackground(round(DANGER, dp(24)));
        }
    }

    void showFloat(final String str) {
        if (this.floatToast == null) {
            return;
        }
        runOnUiThread(() -> {
            this.floatToast.setText(str);
            this.floatToast.setVisibility(0);
            this.floatToast.setAlpha(0.0f);
            this.floatToast.setScaleX(0.7f);
            this.floatToast.setScaleY(0.7f);
            this.floatToast.setTranslationY(dp(42));
            this.floatToast.animate().cancel();
            this.floatToast.animate().alpha(1.0f).scaleX(1.0f).scaleY(1.0f).translationY(0.0f)
                    .setDuration(380L).setInterpolator(new OvershootInterpolator(1.5f))
                    .withEndAction(() -> this.floatToast.postDelayed(() -> {
                        this.floatToast.animate().alpha(0.0f).translationY(-dp(18))
                                .setDuration(260L).setStartDelay(900L)
                                .withEndAction(() -> this.floatToast.setVisibility(8)).start();
                    }, 900L)).start();
        });
    }

    void spring(final View view) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == 0) {
                view.animate().cancel();
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110L).start();
                return false;
            }
            if (action != 1 && action != 3) {
                return false;
            }
            view.animate().cancel();
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300L)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
            return false;
        });
    }

    /* ========== 统一弹窗 ========== */

    LinearLayout dialogContainer(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.bg_card));
        c.setPadding(dp(24), dp(20), dp(24), dp(16));
        if (title != null) {
            c.addView(tv(title, 18, TEXT, true));
        }
        return c;
    }

    LinearLayout dialogButtonRow(String positiveText, String negativeText, final AlertDialog[] holder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(20), 0, 0);
        row.setLayoutParams(lp);
        if (negativeText != null) {
            Button neg = mkSmallBtn(negativeText, SUBTEXT);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, dp(34));
            nlp.setMargins(0, 0, dp(10), 0);
            neg.setLayoutParams(nlp);
            spring(neg);
            neg.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); });
            row.addView(neg);
        }
        if (positiveText != null) {
            Button pos = mkSmallBtn(positiveText, PRIMARY);
            spring(pos);
            pos.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); });
            row.addView(pos);
        }
        return row;
    }

    AlertDialog showDialog(LinearLayout container, boolean cancelable) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(container)
                .setCancelable(cancelable)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
        container.setScaleX(0.92f);
        container.setScaleY(0.92f);
        container.setAlpha(0f);
        container.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.2f)).start();
        return dialog;
    }

    AlertDialog showCustomDialog(String title, String message, View contentView,
            String positiveText, String negativeText, boolean cancelable,
            Runnable onPositive, Runnable onNegative) {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout container = dialogContainer(title);
        if (message != null) {
            TextView msg = tv(message, 14, SUBTEXT, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, title != null ? dp(10) : 0, 0, 0);
            container.addView(msg, lp);
        }
        if (contentView != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, (title != null || message != null) ? dp(10) : 0, 0, 0);
            container.addView(contentView, lp);
        }
        if (positiveText != null || negativeText != null) {
            LinearLayout btnRow = dialogButtonRow(positiveText, negativeText, holder);
            if (onPositive != null || onNegative != null) {
                int idx = 0;
                if (negativeText != null && onNegative != null) {
                    final Button negBtn = (Button) btnRow.getChildAt(idx++);
                    negBtn.setOnClickListener(v -> {
                        onNegative.run();
                        if (holder[0] != null) holder[0].dismiss();
                    });
                } else if (negativeText != null) {
                    idx++;
                }
                if (positiveText != null && onPositive != null) {
                    final Button posBtn = (Button) btnRow.getChildAt(idx);
                    posBtn.setOnClickListener(v -> {
                        onPositive.run();
                        if (holder[0] != null) holder[0].dismiss();
                    });
                }
            }
            container.addView(btnRow);
        }
        AlertDialog dialog = showDialog(container, cancelable);
        holder[0] = dialog;
        return dialog;
    }

    void requestRoot() {
        new Thread(() -> {
            try {
                final boolean z = suExec("id").code == 0;
                runOnUiThread(() -> {
                    rootGranted = z;
                    if (z) {
                        syncService();
                        checkPendingRestore();
                    } else {
                        appendLog("[系统] 未获得 root 权限");
                        promptNoRoot();
                    }
                    updateStatus();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    appendLog("[系统] root 请求失败：" + e.getMessage());
                    promptNoRoot();
                });
            }
        }).start();
    }

    void promptNoRoot() {
        showCustomDialog("需要 Root 权限",
                "未检测到 root 权限。\n\n本应用需在已 root 的环境中运行，请先授予 root 权限（Magisk / KernelSU / APatch），再重新打开本应用。",
                null, "退出", null, false, () -> finish(), null);
    }

    void syncService() {
        new Thread(() -> {
            try {
                final boolean z = isLogdRunning();
                // 检查是否有待恢复的应用 (隐藏后重启, logd 未运行)
                final ArrayList<String> hidden = readHiddenApps();
                final String apkBaseDir = getFilesDir().getAbsolutePath() + "/apks";
                boolean pending = false;
                for (String pkg : hidden) {
                    Res lsRes = suExec("find " + shq(apkBaseDir + "/" + pkg) + " -name '*.apk' 2>/dev/null");
                    if (lsRes.code == 0 && lsRes.out != null && !lsRes.out.trim().isEmpty()) {
                        pending = true;
                        break;
                    }
                }
                final boolean finalPending = pending;
                runOnUiThread(() -> {
                    serviceRunning = z;
                    hasPendingRestore = finalPending;
                    updateStatus();
                    updateToggleBtn();
                    if (z) {
                        appendLog("[系统] 检测到 logd 服务已在运行");
                    }
                    if (finalPending) {
                        appendLog("[系统] 检测到 " + hidden.size() + " 个待恢复应用");
                    }
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    void checkPendingRestore() {
        new Thread(() -> {
            try {
                final ArrayList<String> hidden = readHiddenApps();
                if (hidden.isEmpty()) return;

                final String apkBaseDir = getFilesDir().getAbsolutePath() + "/apks";
                boolean hasApks = false;
                int count = 0;
                for (String pkg : hidden) {
                    Res lsRes = suExec("find " + shq(apkBaseDir + "/" + pkg) + " -name '*.apk' 2>/dev/null");
                    if (lsRes.code == 0 && lsRes.out != null && !lsRes.out.trim().isEmpty()) {
                        hasApks = true;
                        count++;
                    }
                }

                if (hasApks) {
                    final int finalCount = count;
                    runOnUiThread(() -> {
                        appendLog("[恢复] 检测到 " + finalCount + " 个待恢复应用（可能手机异常重启）");
                        showCustomDialog("检测到待恢复应用",
                                        "检测到 " + finalCount + " 个应用未恢复（手机可能异常重启），是否立即恢复？",
                                        null, "恢复", "稍后", true,
                                        () -> restoreApps(), null);
                    });
                }
            } catch (Exception e) {
                appendLog("[恢复] 检测异常: " + e.getMessage());
            }
        }).start();
    }

    // ==================== 隐藏 / 恢复 ====================

    void hideApps() {
        if (!this.rootGranted) {
            appendLog("[错误] 尚未获得 root 权限，请先点击状态卡授权");
            updateStatus();
            return;
        }

        int checkedCount = 0;
        for (AppInfo info : this.allApps) {
            if (info.checked) {
                checkedCount++;
            }
        }
        if (checkedCount == 0 && this.hideDirs.isEmpty()) {
            showFloat("请先勾选应用或添加隐藏路径");
            return;
        }

        final int checkedCountFinal = checkedCount;
        final int hideDirsSize = this.hideDirs.size();
        appendLog("[隐藏] 开始隐藏 " + checkedCount + " 个应用…（隐藏路径 " + this.hideDirs.size() + "）");
        toggleBtn.setEnabled(false);

        new Thread(() -> {
            try {
                // 停止已有 logd
                if (isLogdRunning()) {
                    runOnUiThread(() -> appendLog("[系统] 先停止已有 logd…"));
                    stopLogdProcess();
                }

                // 0. 重新检测路径（列表刷新后 paths 可能为空）
                for (AppInfo info : this.allApps) {
                    if (info.checked && info.paths.isEmpty()) {
                        detectPathsSync(info);
                        appendLog("[路径] 重新检测 " + info.label + "：发现 " + info.paths.size() + " 个路径");
                    }
                }

                // 1. 写配置 (此时路径还存在)
                writeConfig();
                writePathsLog();

                // 2. 保存 APK + 卸载 (保留数据) —— 必须在启动 logd 之前完成
                //    因为如果隐藏路径包含 /data/adb，logd 启动后 su 会失效
                ArrayList<String> uninstalled = new ArrayList<>();
                String apkBaseDir = getFilesDir().getAbsolutePath() + "/apks";
                if (checkedCountFinal > 0) {
                    suExec("mkdir -p " + shq(apkBaseDir));
                    for (AppInfo info : this.allApps) {
                        if (info.checked) {
                            appendLog("[隐藏] 处理 " + info.label + " (" + info.pkg + ")…");
                            // a. 获取 APK 路径
                            Res pathRes = suExec("pm path " + shq(info.pkg));
                            if (pathRes.code == 0 && pathRes.out != null && pathRes.out.contains("package:")) {
                                String apkDir = apkBaseDir + "/" + info.pkg;
                                suExec("mkdir -p " + shq(apkDir));
                                int apkCount = 0;
                                for (String line : pathRes.out.trim().split("\n")) {
                                    if (line.startsWith("package:")) {
                                        String apkPath = line.substring(8).trim();
                                        String apkName = apkPath.substring(apkPath.lastIndexOf('/') + 1);
                                        suExec("cp " + shq(apkPath) + " " + shq(apkDir + "/" + apkName));
                                        apkCount++;
                                    }
                                }
                                appendLog("[隐藏] 已保存 " + apkCount + " 个 APK → " + apkDir);
                            } else {
                                appendLog("[隐藏] 警告: 无法获取 APK 路径, 跳过保存");
                            }
                            // b. 卸载 (保留数据 -k)
                            Res res = suExec("pm uninstall -k --user 0 " + shq(info.pkg));
                            if (res.code == 0) {
                                uninstalled.add(info.pkg);
                                appendLog("[隐藏] 已卸载 " + info.pkg);
                            } else {
                                appendLog("[隐藏] 卸载失败 " + info.pkg + "：" + (res.out != null ? res.out.trim() : ""));
                            }
                        }
                    }
                    writeHiddenApps(uninstalled);
                }

                // 3. 启动 logd (bind mount) —— 放在最后，因为隐藏 /data/adb 后 su 会失效
                appendLog("[隐藏] 启动 logd 进行 bind mount…");
                startLogd();

                // 4. 清理残留的 sh/su/sh-c 进程（如果 su 还可用的话）
                try {
                    killLeftoverShells();
                } catch (Exception ignored) {
                    // su 可能已失效，忽略
                }

                appendLog("[隐藏] 完成: 已隐藏 " + uninstalled.size() + " 个应用，" + hideDirsSize + " 个路径");

                runOnUiThread(() -> {
                    toggleBtn.setEnabled(true);
                    showFloat("隐藏已启动");
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    appendLog("[错误] 隐藏失败：" + e.getMessage());
                    toggleBtn.setEnabled(true);
                });
            }
        }).start();
    }

    void startLogd() {
        try {
            String shq = shq(ensureBinary().getAbsolutePath());
            // 使用 & 后台启动 daemon, 使 su/sh 进程立即退出, 不残留
            Process start = new ProcessBuilder("su", "-c",
                    "chmod 755 " + shq + " ; " + shq + " " + shq(this.cfgFile.getAbsolutePath())
                            + " >>" + shq(this.logFile.getAbsolutePath()) + " 2>&1 &")
                    .redirectErrorStream(true).start();
            this.serviceProc = start;
            this.serviceRedirectMode = true;
            this.serviceRunning = true;
            runOnUiThread(() -> {
                updateStatus();
                appendLog("[系统] 服务进程已启动，配置：" + this.cfgFile.getAbsolutePath());
                showFloat("隐藏已启动");
            });
            startLogTail();

            BufferedReader reader = new BufferedReader(new InputStreamReader(start.getInputStream()));
            String readLine;
            while ((readLine = reader.readLine()) != null) {
                if (readLine.startsWith("[20")) {
                    int idx = readLine.indexOf("] ");
                    if (idx > 0) {
                        readLine = readLine.substring(idx + 2);
                    }
                }
                if (!readLine.isEmpty()) {
                    displayLog(readLine);
                }
            }
            stopLogTail();
            this.serviceRedirectMode = false;
            this.serviceProc = null;

            // 验证守护进程是否存活
            // 优先用 su 验证，失败则回退到直接读 pid 文件（隐藏 /data/adb 后 su 可能失效）
            boolean verified = false;
            for (int i = 0; i < 3; i++) {
                try {
                    Thread.sleep(500L);
                } catch (Exception ignored) {
                }
                String pidPath = this.cfgFile.getParent() + "/logd.pid";
                String pidStr = "";
                // 先尝试 su 方式
                try {
                    Res pidRes = suExec("cat " + shq(pidPath) + " 2>/dev/null");
                    pidStr = (pidRes.out != null) ? pidRes.out.trim() : "";
                    if (!pidStr.isEmpty() && suExec("kill -0 " + pidStr + " 2>/dev/null").code == 0) {
                        verified = true;
                        break;
                    }
                } catch (Exception e) {
                    // su 可能不可用（如隐藏了 /data/adb 后 su 失效），回退到直接读文件
                }
                // 回退：直接读 pid 文件（如果文件权限允许）
                if (!verified) {
                    try {
                        java.io.File pidFile = new java.io.File(pidPath);
                        if (pidFile.exists()) {
                            byte[] buf = new byte[32];
                            java.io.FileInputStream fis = new java.io.FileInputStream(pidFile);
                            int len = fis.read(buf);
                            fis.close();
                            if (len > 0) {
                                pidStr = new String(buf, 0, len).trim();
                                // 无法用 kill -0 验证，但只要 pid 文件存在就认为启动成功
                                if (!pidStr.isEmpty()) {
                                    verified = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (verified) {
                this.serviceRunning = true;
                runOnUiThread(() -> {
                    updateStatus();
                    appendLog("[系统] 守护进程已就绪");
                });
                startLogTail();
                startServiceWatcher();
                return;
            }

            try {
                StringBuilder sb = new StringBuilder();
                if (this.logFile.exists()) {
                    BufferedReader reader2 = new BufferedReader(new FileReader(this.logFile));
                    int count = 0;
                    String line;
                    while ((line = reader2.readLine()) != null) {
                        if (count < 20) {
                            sb.append(line);
                            sb.append('\n');
                            count++;
                        } else {
                            sb.delete(0, sb.indexOf("\n") + 1);
                            sb.append(line);
                            sb.append('\n');
                        }
                    }
                    reader2.close();
                }
                final String trim = sb.toString().trim();
                runOnUiThread(() -> {
                    serviceRunning = false;
                    updateStatus();
                    appendLog("[系统] 服务已退出");
                    if (trim.length() > 0) {
                        appendLog("[诊断] 日志末尾：\n" + trim);
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    serviceRunning = false;
                    updateStatus();
                    appendLog("[系统] 服务已退出");
                });
            }
        } catch (final Exception e) {
            runOnUiThread(() -> appendLog("[错误] 启动失败：" + e.getMessage()));
        }
    }

    void restoreApps() {
        if (!this.rootGranted) {
            appendLog("[错误] 尚未获得 root 权限");
            updateStatus();
            return;
        }
        appendLog("[恢复] 正在恢复…");
        showFloat("正在恢复…");
        toggleBtn.setEnabled(false);
        new Thread(() -> {
            try {
                stopLogTail();
                stopServiceWatcher();
                this.serviceRedirectMode = false;

                boolean logdStopped = stopLogdProcess();

                // 如果是通过 su 停止的，再做一次 cleanup 兜底；
                // 如果是通过控制通道停止的，logd 退出时已经 umount 了，且 su 已失效，跳过 cleanup
                if (logdStopped && isSuAvailable()) {
                    cleanupMounts();
                }

                // APK 恢复需要 su，如果 su 不可用则跳过并提示
                boolean suOk = isSuAvailable();
                ArrayList<String> hidden = readHiddenApps();
                String apkBaseDir = getFilesDir().getAbsolutePath() + "/apks";
                if (suOk) {
                    for (String pkg : hidden) {
                        appendLog("[恢复] 恢复 " + pkg + "…");
                        // 从保存的 APK 重新安装
                        String apkDir = apkBaseDir + "/" + pkg;
                        Res lsRes = suExec("find " + shq(apkDir) + " -name '*.apk'");
                        if (lsRes.code == 0 && lsRes.out != null && !lsRes.out.trim().isEmpty()) {
                            StringBuilder cmd = new StringBuilder("pm install -r --user 0");
                            for (String apk : lsRes.out.trim().split("\n")) {
                                apk = apk.trim();
                                if (!apk.isEmpty()) {
                                    cmd.append(" ").append(shq(apk));
                                }
                            }
                            Res res = suExec(cmd.toString());
                            if (res.code == 0) {
                                appendLog("[恢复] 已安装 " + pkg);
                                // 删除已恢复的 APK
                                suExec("rm -rf " + shq(apkDir));
                            } else {
                                appendLog("[恢复] 安装失败 " + pkg + "：" + (res.out != null ? res.out.trim() : ""));
                            }
                        } else {
                            appendLog("[恢复] 找不到保存的 APK: " + apkDir);
                        }
                    }
                    // 不清除 hidden_apps.txt 和 hideDirs，保留勾选记录和手动添加的路径
                    suExec("rm -rf " + shq(apkBaseDir));
                } else if (hidden.size() > 0) {
                    appendLog("[提示] su 不可用，跳过 APK 恢复（应用仍处于隐藏状态）");
                    appendLog("[提示] 重启设备后可重新获取 su 并恢复应用");
                }

                runOnUiThread(() -> {
                    serviceRunning = false;
                    updateStatus();
                    updateHomeCounts();
                    toggleBtn.setEnabled(true);
                    showFloat("已恢复");
                    appendLog("[恢复] 恢复完成");
                    // 刷新应用列表（恢复的应用现在已安装，会出现在列表中并自动勾选）
                    if (suOk) {
                        loadAppsAsync();
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    appendLog("[错误] 恢复失败：" + e.getMessage());
                    toggleBtn.setEnabled(true);
                });
            }
        }).start();
    }

    void stopService() {
        stopLogTail();
        stopServiceWatcher();
        this.serviceRedirectMode = false;
        appendLog("[系统] 正在停止服务…");
        showFloat("服务已停止");
        if (toggleBtn != null) toggleBtn.setEnabled(false);
        new Thread(() -> {
            try {
                boolean logdStopped = stopLogdProcess();

                // 如果 su 可用，再做一次 cleanup 兜底
                boolean suOk = isSuAvailable();
                if (logdStopped && suOk) {
                    cleanupMounts();
                }

                ArrayList<String> hidden = readHiddenApps();
                String apkBaseDir = getFilesDir().getAbsolutePath() + "/apks";
                if (suOk) {
                    for (String pkg : hidden) {
                        appendLog("[恢复] 恢复 " + pkg + "…");
                        String apkDir = apkBaseDir + "/" + pkg;
                        Res lsRes = suExec("find " + shq(apkDir) + " -name '*.apk'");
                        if (lsRes.code == 0 && lsRes.out != null && !lsRes.out.trim().isEmpty()) {
                            StringBuilder cmd = new StringBuilder("pm install -r --user 0");
                            for (String apk : lsRes.out.trim().split("\n")) {
                                apk = apk.trim();
                                if (!apk.isEmpty()) {
                                    cmd.append(" ").append(shq(apk));
                                }
                            }
                            Res res = suExec(cmd.toString());
                            if (res.code == 0) {
                                appendLog("[恢复] 已安装 " + pkg);
                                suExec("rm -rf " + shq(apkDir));
                            } else {
                                appendLog("[恢复] 安装失败 " + pkg + "：" + (res.out != null ? res.out.trim() : ""));
                            }
                        } else {
                            appendLog("[恢复] 找不到保存的 APK: " + apkDir);
                        }
                    }
                    // 不清除 hidden_apps.txt 和 hideDirs，保留勾选记录和手动添加的路径
                    suExec("rm -rf " + shq(apkBaseDir));
                } else if (hidden.size() > 0) {
                    appendLog("[提示] su 不可用，跳过 APK 恢复");
                }

                runOnUiThread(() -> {
                    serviceRunning = false;
                    updateStatus();
                    updateHomeCounts();
                    if (toggleBtn != null) toggleBtn.setEnabled(true);
                    // 刷新应用列表（恢复的应用现在已安装）
                    if (suOk) {
                        loadAppsAsync();
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    appendLog("[错误] 停止服务失败：" + e.getMessage());
                    if (toggleBtn != null) toggleBtn.setEnabled(true);
                });
            }
        }).start();
    }

    boolean isLogdRunning() {
        try {
            String pidPath = this.cfgFile.getParent() + "/logd.pid";
            // 优先用 su 验证
            try {
                Res r = suExec("cat " + shq(pidPath) + " 2>/dev/null");
                String pidStr = (r.out != null) ? r.out.trim() : "";
                if (!pidStr.isEmpty() && suExec("kill -0 " + pidStr + " 2>/dev/null").code == 0) {
                    return true;
                }
            } catch (Exception ignored) {
                // su 可能失效，回退到直接读 pid 文件
            }
            // 回退：直接读 pid 文件判断进程是否存在
            java.io.File pidFile = new java.io.File(pidPath);
            if (pidFile.exists()) {
                byte[] buf = new byte[32];
                java.io.FileInputStream fis = new java.io.FileInputStream(pidFile);
                int len = fis.read(buf);
                fis.close();
                return len > 0;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 检查 su 是否可用
     */
    boolean isSuAvailable() {
        try {
            Res r = suExec("echo ok");
            return r.code == 0 && r.out != null && r.out.trim().equals("ok");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过控制文件向 logd 守护进程发送命令
     * 用于 su 被隐藏后无法通过 su 控制的场景
     * 原理: App 写入命令文件 logd.cmd，logd 每秒轮询检测
     * @param cmd 命令: unhide, stop, exit, ping
     * @return 是否发送成功（文件写入成功即返回 true）
     */
    boolean sendControlCommand(String cmd) {
        String cmdPath = this.cfgFile.getParent() + "/logd.cmd";
        try {
            // 写入命令文件
            java.io.FileWriter writer = new java.io.FileWriter(cmdPath);
            writer.write(cmd + "\n");
            writer.flush();
            writer.close();
            // 设置权限，让 root 的 logd 也能读（其实 App 私有目录 root 本来就能读）
            java.io.File f = new java.io.File(cmdPath);
            f.setReadable(true, false);
            f.setWritable(true, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 停止 logd 守护进程
     * @return true=成功停止(或本来就没运行), false=失败
     */
    boolean stopLogdProcess() {
        String pidPath = this.cfgFile.getParent() + "/logd.pid";
        boolean stopped = false;

        // 先检查是否在运行
        java.io.File pidFile = new java.io.File(pidPath);
        if (!pidFile.exists()) {
            return true; // 本来就没运行，也算成功
        }

        // 1. 优先用 su 停止（正常情况）
        try {
            Res r = suExec("cat " + shq(pidPath) + " 2>/dev/null");
            String pidStr = (r.out != null) ? r.out.trim() : "";
            if (!pidStr.isEmpty()) {
                suExec("kill -TERM " + pidStr + " 2>/dev/null");
                appendLog("[系统] 停止守护进程 pid=" + pidStr);
                stopped = true;
            }
            suExec("pkill -TERM -x logd 2>/dev/null");
            suExec("rm -f " + shq(pidPath) + " 2>/dev/null");
            Thread.sleep(500);
            killLeftoverShells();
        } catch (Exception e) {
            // su 可能失效了，回退到控制文件方式
        }

        // 2. su 失效时，通过控制文件发送 unhide 命令恢复
        if (!stopped) {
            appendLog("[系统] su 不可用，尝试通过控制通道恢复…");
            if (sendControlCommand("unhide")) {
                appendLog("[系统] 已发送恢复命令，等待 logd 退出（最多 5 秒）…");
                // 等待进程退出（logd 每秒轮询一次，加上清理时间，最多等 5 秒）
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {}
                    java.io.File pf = new java.io.File(pidPath);
                    if (!pf.exists()) {
                        stopped = true;
                        break;
                    }
                }
                if (stopped) {
                    appendLog("[系统] 恢复成功，logd 已退出");
                } else {
                    appendLog("[系统] 警告：等待超时，请检查 logd 日志");
                }
            } else {
                appendLog("[错误] 控制通道写入失败，无法发送恢复命令");
                appendLog("[错误] 请重启设备以恢复");
            }
        }

        return stopped;
    }

    /**
     * 清理遗留的 sh / su 进程
     * 使用变量间接引用, 使清理命令自身的 cmdline 不包含目标路径字面量, 避免误杀自身
     * 在每次隐藏完成后或停止服务时调用
     */
    void killLeftoverShells() {
        try {
            // 使用变量间接引用: sh -c 的 cmdline 中出现 $D/$P 而非展开值
            // 因此清理进程自身不会匹配到目标路径, 避免误杀自身
            Res res = suExec(
                "D=com.example.logd; "
                + "P=/data/user/0/$D/files; "
                + "for p in /proc/[0-9]*; do "
                + "  pid=${p#/proc/}; "
                + "  [ \"$pid\" = \"$$\" ] && continue; "
                + "  [ \"$pid\" = \"$PPID\" ] && continue; "
                + "  [ -f \"$p/cmdline\" ] || continue; "
                + "  cmd=$(cat \"$p/cmdline\" 2>/dev/null | tr '\\0' ' '); "
                + "  [ -z \"$cmd\" ] && continue; "
                + "  case \"$cmd\" in "
                + "    *\"$P\"*) "
                + "      kill -9 \"$pid\" 2>/dev/null && echo \"killed $pid\"; "
                + "      ;; "
                + "  esac; "
                + "done"
            );
            if (res.out != null && !res.out.trim().isEmpty()) {
                appendLog("[清理] 已清理残留进程: " + res.out.trim().replace("\n", ", "));
            }
        } catch (Exception e) {
            appendLog("[清理] 清理残留进程失败: " + e.getMessage());
        }
    }

    void cleanupMounts() {
        try {
            File binary = ensureBinary();
            String binPath = shq(binary.getAbsolutePath());
            String cfgPath = shq(this.cfgFile.getAbsolutePath());
            Res res = suExec(binPath + " --cleanup " + cfgPath);
            appendLog("[清理] cleanup 退出码 " + res.code
                    + (res.out != null && !res.out.trim().isEmpty() ? "：" + res.out.trim() : ""));
        } catch (Exception e) {
            appendLog("[清理] cleanup 失败：" + e.getMessage());
        }
    }

    void startServiceWatcher() {
        if (this.watcherRunning) {
            return;
        }
        this.watcherRunning = true;
        Thread thread = new Thread(() -> {
            while (this.watcherRunning) {
                try {
                    Thread.sleep(3000L);
                    if (!isLogdRunning()) {
                        this.watcherRunning = false;
                        runOnUiThread(() -> {
                            serviceRunning = false;
                            updateStatus();
                            appendLog("[系统] 服务已退出");
                            showFloat("服务已停止");
                        });
                        return;
                    }
                } catch (Exception ignored) {
                    return;
                }
            }
        });
        this.watcherThread = thread;
        thread.start();
    }

    void stopServiceWatcher() {
        this.watcherRunning = false;
        Thread thread = this.watcherThread;
        if (thread != null) {
            thread.interrupt();
            this.watcherThread = null;
        }
    }

    // ==================== Config ====================

    void writeConfig() {
        ArrayList<String> appPaths = new ArrayList<>();
        for (AppInfo info : this.allApps) {
            if (info.checked) {
                for (String p : info.paths) {
                    if (!appPaths.contains(p)) {
                        appPaths.add(p);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("# logd config\ninterval:2\n\n");
        for (String path : appPaths) {
            sb.append("hide:");
            sb.append(path);
            sb.append('\n');
        }
        for (String dir : this.hideDirs) {
            sb.append("custom:");
            sb.append(dir);
            sb.append('\n');
        }
        String content = sb.toString();
        try {
            try {
                writeConfigFile(this.cfgFile, content);
            } catch (Exception e) {
                if (this.rootGranted) {
                    try {
                        writeConfigAsRoot(content);
                        appendLog("[配置] 已保存(root)：应用 " + appPaths.size() + " 路径，自定义 " + this.hideDirs.size() + " 路径 → " + this.cfgFile.getAbsolutePath());
                        return;
                    } catch (Exception e2) {
                        appendLog("[错误] 保存配置失败：" + e.getMessage());
                        return;
                    }
                }
                appendLog("[错误] 保存配置失败：" + e.getMessage());
                return;
            }
        } catch (Exception unused) {
            if (this.cfgFile.exists() && !this.cfgFile.delete()) {
                appendLog("[配置] 清理旧配置失败，改用 root 写入");
            }
            try {
                writeConfigFile(this.cfgFile, content);
            } catch (Exception ignored) {
            }
        }
        appendLog("[配置] 已保存：应用 " + appPaths.size() + " 路径，自定义 " + this.hideDirs.size() + " 路径 → " + this.cfgFile.getAbsolutePath());
    }

    void writeConfigFile(File file, String str) throws IOException {
        FileWriter fw = new FileWriter(file);
        fw.write(str);
        fw.close();
    }

    void writeConfigAsRoot(String str) throws Exception {
        Process start = new ProcessBuilder("su", "-c",
                "cat > " + shq(this.cfgFile.getAbsolutePath()) + " && chmod 666 " + shq(this.cfgFile.getAbsolutePath())).start();
        OutputStream os = start.getOutputStream();
        os.write(str.getBytes("UTF-8"));
        os.flush();
        os.close();
        if (start.waitFor() != 0) {
            throw new IOException("root 写入失败");
        }
    }

    // ==================== Log ====================

    void appendLog(String str) {
        final String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date()) + "] " + str + "\n";
        writeLogFile(line);
        runOnUiThread(() -> {
            this.logBuffer.append(line);
            if (this.logBuffer.length() > 30000) {
                this.logBuffer.delete(0, 15000);
            }
            this.logView.setText(this.logBuffer.toString());
            scrollToLogBottom();
        });
    }

    void displayLog(String str) {
        final String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date()) + "] " + str + "\n";
        runOnUiThread(() -> {
            this.logBuffer.append(line);
            if (this.logBuffer.length() > 30000) {
                this.logBuffer.delete(0, 15000);
            }
            this.logView.setText(this.logBuffer.toString());
            scrollToLogBottom();
        });
    }

    void startLogTail() {
        stopLogTail();
        this.logTailOffset = this.logFile.exists() ? this.logFile.length() : 0L;
        Thread thread = new Thread(() -> {
            try {
                RandomAccessFile raf = new RandomAccessFile(this.logFile, "r");
                raf.seek(this.logTailOffset);
                while (this.serviceRunning && !Thread.interrupted()) {
                    long length;
                    try {
                        length = raf.length();
                    } catch (Exception e) {
                        raf = new RandomAccessFile(this.logFile, "r");
                        this.logTailOffset = raf.length();
                        raf.seek(this.logTailOffset);
                        Thread.sleep(100L);
                        continue;
                    }
                    if (length < this.logTailOffset) {
                        raf.seek(0L);
                        this.logTailOffset = 0L;
                    } else {
                        if (length > this.logTailOffset) {
                            byte[] bArr = new byte[(int) (length - this.logTailOffset)];
                            raf.readFully(bArr);
                            this.logTailOffset = length;
                            String[] split = new String(bArr, "UTF-8").split("\n");
                            for (String s : split) {
                                String trim = s.trim();
                                if (!trim.isEmpty() && trim.startsWith("[20")) {
                                    int idx = trim.indexOf("] ");
                                    if (idx > 0) {
                                        trim = trim.substring(idx + 2);
                                    }
                                    if (!trim.isEmpty()) {
                                        displayLog(trim);
                                    }
                                }
                            }
                        }
                        Thread.sleep(100L);
                    }
                }
                raf.close();
            } catch (Exception ignored) {
            }
        });
        this.logTailThread = thread;
        thread.start();
    }

    void stopLogTail() {
        Thread thread = this.logTailThread;
        if (thread != null) {
            thread.interrupt();
            this.logTailThread = null;
        }
    }

    void clearLog() {
        this.logBuffer.setLength(0);
        this.logView.setText("");
        synchronized (this.logLock) {
            try {
                if (this.logFile.exists()) {
                    this.logFile.delete();
                }
            } catch (Exception ignored) {
            }
        }
        this.logTailOffset = 0L;
    }

    void writeLogFile(String str) {
        synchronized (this.logLock) {
            try {
                FileWriter fw = new FileWriter(this.logFile, true);
                fw.append(str);
                fw.close();
                if (this.logFile.length() > LOG_MAX_SIZE) {
                    trimLogFile();
                }
            } catch (Exception ignored) {
            }
        }
    }

    void trimLogFile() {
        try {
            long length = this.logFile.length();
            if (length <= LOG_MAX_SIZE) {
                return;
            }
            long half = length / 2;
            int size = (int) (length - half);
            byte[] bArr = new byte[size];
            RandomAccessFile raf = new RandomAccessFile(this.logFile, "r");
            raf.seek(half);
            raf.readFully(bArr);
            raf.close();
            int i = 0;
            while (i < size && bArr[i] != 10) {
                i++;
            }
            String str;
            if (i >= 0 && i < size - 1) {
                str = new String(bArr, i + 1, (size - i) - 1, "UTF-8");
            } else {
                str = new String(bArr, "UTF-8");
            }
            FileWriter fw = new FileWriter(this.logFile, false);
            fw.write(str);
            fw.close();
        } catch (Exception ignored) {
        }
    }

    void loadPreviousLogs() {
        try {
            if (this.logFile.exists()) {
                byte[] bArr = new byte[(int) this.logFile.length()];
                FileInputStream fis = new FileInputStream(this.logFile);
                fis.read(bArr);
                fis.close();
                String str = new String(bArr, "UTF-8");
                if (str.isEmpty()) {
                    return;
                }
                this.logBuffer.append(str.replaceAll("\\[\\d{4}-\\d{2}-\\d{2} (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]", "[$1]"));
                this.logView.setText(this.logBuffer.toString());
                scrollToLogBottom();
            }
        } catch (Exception ignored) {
        }
    }

    void scrollToLogBottom() {
        Runnable scroll = () -> {
            int y = this.logView.getHeight() - this.logScroll.getHeight();
            if (y > 0) {
                this.logScroll.scrollTo(0, y);
            }
        };
        this.logView.post(scroll);
        this.logView.postDelayed(scroll, 50L);
        this.logView.postDelayed(scroll, 200L);
    }

    void exportLog() {
        final String sb = this.logBuffer.toString();
        if (sb.trim().isEmpty()) {
            showFloat("暂无日志可导出");
            return;
        }
        final String path = "/sdcard/" + ("logd日志_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt");
        new Thread(() -> {
            try {
                if (this.rootGranted) {
                    File file = new File(getCacheDir(), "export_log.txt");
                    FileWriter fw = new FileWriter(file);
                    fw.write(sb);
                    fw.close();
                    Res res = suExec("cp " + shq(file.getAbsolutePath()) + " " + shq(path) + " && chmod 666 " + shq(path));
                    file.delete();
                    if (res.code == 0) {
                        finishExport(path);
                        return;
                    }
                    Process start = new ProcessBuilder("su", "-c", "cat > " + shq(path)).start();
                    OutputStream os = start.getOutputStream();
                    os.write(sb.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    if (start.waitFor() == 0) {
                        finishExport(path);
                        return;
                    }
                    throw new IOException("root 写入失败：" + res.out.trim());
                }
                FileWriter fw2 = new FileWriter(path);
                fw2.write(sb);
                fw2.close();
                finishExport(path);
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    appendLog("[错误] 导出日志失败：" + e.getMessage());
                    showFloat("导出失败");
                });
            }
        }).start();
    }

    void finishExport(final String path) {
        runOnUiThread(() -> {
            appendLog("[系统] 日志已导出：" + path);
            showFloat("日志已导出到存储根目录");
        });
    }

    void openBugReport() {
        try {
            Intent intent = new Intent("android.intent.action.VIEW", Uri.parse("https://github.com/Hoerire/mount-namespace/issues"));
            intent.setPackage(null);
            if (intent.resolveActivity(getPackageManager()) == null) {
                intent.setPackage(null);
            }
            startActivity(intent);
        } catch (Exception e) {
            appendLog("[错误] 打开反馈链接失败：" + e.getMessage());
        }
    }

    void openDocs() {
        try {
            startActivity(new Intent("android.intent.action.VIEW", Uri.parse("https://github.com/Hoerire/mount-namespace")));
        } catch (Exception e) {
            appendLog("[错误] 打开说明文档失败：" + e.getMessage());
        }
    }

    View principleHeader(String str) {
        TextView tv = tv(str, 15, PRIMARY, true);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    View principleItem(String str) {
        TextView tv = tv("· " + str, 14, TEXT, false);
        tv.setPadding(dp(8), dp(3), dp(6), dp(3));
        tv.setLineSpacing((float) dp(2), 1.0f);
        return tv;
    }

    void showPrinciple() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(22), dp(14), dp(22), dp(18));
        scrollView.addView(linearLayout);
        linearLayout.addView(principleHeader("一、核心思路"));
        linearLayout.addView(principleItem("两段式架构：应用负责配置、授权与部署，原生 logd 进程专职「前台监听 + 系统级隐藏」，职责清晰、互不干扰。"));
        linearLayout.addView(principleItem("另有「脚本管理 / 轻量终端」模块，支持以 root 权限运行 SH 脚本与编译后的二进制文件。"));
        linearLayout.addView(principleItem("通过系统事件日志感知前台应用变化，事件驱动、按需处理，空闲时进程开销趋近于零。"));
        linearLayout.addView(principleHeader("二、logd 服务与隐藏机制"));
        linearLayout.addView(principleItem("应用以 root 权限拉起 logd，二者通过标准输出管道通信，运行日志实时回传界面；停止服务时自动恢复被修改过的应用。"));
        linearLayout.addView(principleItem("logd 订阅系统事件日志（logcat -b events），仅过滤两类窗口事件：wm_on_resume_called 与 wm_on_top_resumed_gained_called，用 poll() 阻塞等待、事件驱动消费。"));
        linearLayout.addView(principleItem("隐藏流程：先启动 logd 进行 bind mount 隔离目录，再保存 APK 到私有目录并 pm uninstall -k --user 0 卸载应用（保留数据）。"));
        linearLayout.addView(principleItem("每次隐藏/恢复后回读应用状态校验，命令失败或状态未变会输出警告，确保执行结果可靠。"));
        linearLayout.addView(principleHeader("三、配置管理"));
        linearLayout.addView(principleItem("「隐藏应用」勾选应用后自动检测存储路径，点击「隐藏」时卸载应用并写入配置，logd 启动后对这些路径进行 Mount Namespace 隔离。"));
        linearLayout.addView(principleItem("配置写入应用私有目录 config.txt，保存时自动修正文件权限，保证应用与 logd 均可读写；root 写入失败时自动回退重试。"));
        linearLayout.addView(principleItem("logd 每次启动时读取最新配置，修改后重启服务即可热加载，无需重新编译。"));
        linearLayout.addView(principleHeader("四、脚本管理 & 轻量终端"));
        linearLayout.addView(principleItem("通过 root 浏览系统各目录（含 /data 等受限路径），自动识别 ELF 二进制并直接执行，SH 脚本则按脚本运行，并注入完整环境变量。"));
        linearLayout.addView(principleItem("交互式运行窗口实时回显输出，底部输入框可随时向进程发送数字或内容，满足二进制程序的选择 / 输入需求。"));
        linearLayout.addView(principleItem("输出自动过滤 ANSI 颜色与转义码，避免乱码；轻量终端支持常驻 root shell、逐行输入与直接运行脚本。"));
        linearLayout.addView(principleHeader("五、进程清理与日志"));
        linearLayout.addView(principleItem("脚本执行结束后，按 cgroup 归属 + 执行链 PID 双重匹配，清理本次运行遗留的全部进程（包括但不限于 su、sh 及其子进程），并附 PID 与进程名写入日志。"));
        linearLayout.addView(principleItem("运行日志实时展示，支持一键导出到存储卡根目录（root 写入绕过分区存储限制），便于排查与反馈。"));
        showCustomDialog("说明文档", null, scrollView, "已了解", null, true, null, null);
    }

    // ==================== Scripts ====================

    void loadScriptPath() {
        try {
            if (this.scriptPathPref.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(this.scriptPathPref));
                String line = reader.readLine();
                reader.close();
                if (line == null || line.trim().isEmpty()) {
                    return;
                }
                this.scriptPath = line.trim();
            }
        } catch (Exception ignored) {
        }
    }

    void saveScriptPath() {
        try {
            FileWriter fw = new FileWriter(this.scriptPathPref);
            fw.write(this.scriptPath);
            fw.close();
        } catch (Exception ignored) {
        }
    }

    void buildScripts() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(6), dp(6), dp(16), dp(6));
        linearLayout.setBackground(getDrawable(R.drawable.bg_topbar));
        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(icon(R.drawable.ic_back, TEXT));
        imageView.setPadding(dp(12), dp(8), dp(14), dp(8));
        linearLayout.addView(imageView);
        linearLayout.addView(tv("脚本管理", 20, TEXT, true));
        this.scriptContent.addView(linearLayout);
        imageView.setOnClickListener(v -> showHome());

        this.scriptScroll = new ScrollView(this);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(dp(16), dp(14), dp(16), dp(32));
        this.scriptScroll.addView(linearLayout2);
        this.scriptContent.addView(this.scriptScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(0);
        linearLayout3.setGravity(16);
        EditText editText = new EditText(this);
        this.scriptPathInput = editText;
        editText.setHint("输入脚本目录路径，如 /sdcard/scripts");
        this.scriptPathInput.setTextSize(13.0f);
        this.scriptPathInput.setHintTextColor(SUBTEXT);
        this.scriptPathInput.setTextColor(TEXT);
        this.scriptPathInput.setSingleLine(true);
        this.scriptPathInput.setTypeface(Typeface.MONOSPACE);
        this.scriptPathInput.setBackground(getDrawable(R.drawable.bg_search));
        this.scriptPathInput.setPadding(dp(14), dp(11), dp(14), dp(11));
        this.scriptPathInput.setImeOptions(2);
        this.scriptPathInput.setOnEditorActionListener((textView, i, keyEvent) -> {
            if (i != 2 && i != 6) {
                return false;
            }
            goScriptPath();
            return true;
        });
        linearLayout3.addView(this.scriptPathInput, new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button button = new Button(this);
        button.setText("打开");
        button.setTextColor(-1);
        button.setTextSize(13.0f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setBackground(round(-13654417, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(46));
        lp.setMargins(dp(10), 0, 0, 0);
        linearLayout3.addView(button, lp);
        linearLayout2.addView(linearLayout3, new LinearLayout.LayoutParams(-1, -2));
        button.setOnClickListener(v -> goScriptPath());
        spring(button);

        TextView tv = tv("操作：输入脚本目录路径后点「打开」；点击文件夹逐级浏览；点击脚本文件即可运行（可勾选使用 SU 权限）。默认目录为上次保存的路径。", 12, SUBTEXT, false);
        tv.setLineSpacing(dp(2), 1.0f);
        tv.setPadding(dp(2), dp(12), dp(2), dp(6));
        linearLayout2.addView(tv);

        LinearLayout linearLayout4 = new LinearLayout(this);
        this.scriptFileBox = linearLayout4;
        linearLayout4.setOrientation(1);
        linearLayout2.addView(this.scriptFileBox);
    }

    void goScriptPath() {
        String trim = this.scriptPathInput.getText().toString().trim();
        if (trim.isEmpty()) {
            appendLog("[脚本] 请输入目录路径");
            return;
        }
        this.scriptPath = trim;
        saveScriptPath();
        refreshFileList();
    }

    void refreshFileList() {
        this.scriptPathInput.setText(this.scriptPath);
        this.scriptFileBox.removeAllViews();
        File file = new File(this.scriptPath);
        final File parentFile = file.getParentFile();

        if (parentFile != null) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(2), dp(9), dp(2), dp(9));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));
            ImageView imageView = new ImageView(this);
            imageView.setImageDrawable(icon(R.drawable.ic_up, TEXT));
            imageView.setPadding(dp(4), dp(4), dp(8), dp(4));
            row.addView(imageView);
            row.addView(tv("上一级  " + parentFile.getAbsolutePath(), 14, TEXT, false));
            this.scriptFileBox.addView(row);
            row.setOnClickListener(v -> {
                scriptPath = parentFile.getAbsolutePath();
                saveScriptPath();
                refreshFileList();
            });
            spring(row);
        }

        List<FsEntry> list = new ArrayList<>();
        boolean ok = false;
        try {
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File f : listFiles) {
                    FsEntry entry = new FsEntry(f.getName(), f.isDirectory());
                    entry.size = f.length();
                    list.add(entry);
                }
                ok = true;
            }
        } catch (Exception ignored) {
        }
        if (!ok && this.rootGranted) {
            list = rootList(this.scriptPath);
            if (list != null) ok = true;
        }

        if (list == null || list.isEmpty()) {
            String msg = list == null ? "无法读取该目录：无访问权限或路径无效" : (ok ? "目录为空" : "目录为空或无法读取（可尝试勾选 root 权限）");
            TextView tv = tv(msg, 14, SUBTEXT, false);
            tv.setGravity(17);
            tv.setPadding(0, dp(30), 0, dp(30));
            this.scriptFileBox.addView(tv);
            return;
        }

        Collections.sort(list, (a, b) -> {
            if (a.isDir != b.isDir) {
                return a.isDir ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        for (final FsEntry entry : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null));

            ImageView imageView2 = new ImageView(this);
            imageView2.setImageDrawable(icon(entry.isDir ? R.drawable.ic_folder : R.drawable.ic_file, entry.isDir ? -11893528 : -13654417));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(0, 0, dp(12), 0);
            row.addView(imageView2, lp);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(1);
            info.addView(tv(entry.name, 14, TEXT, true));
            String sizeStr;
            if (entry.isDir) {
                sizeStr = "文件夹";
            } else if (entry.size <= 0) {
                sizeStr = "文件";
            } else if (entry.size < 1024) {
                sizeStr = entry.size + " B";
            } else {
                sizeStr = String.format("%.1f KB", entry.size / 1024.0);
            }
            info.addView(tv(sizeStr, 12, SUBTEXT, false));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1.0f));

            this.scriptFileBox.addView(row);
            final File file3 = new File(file, entry.name);
            row.setOnClickListener(v -> {
                if (!entry.isDir) {
                    runScriptDialog(file3);
                } else {
                    scriptPath = file3.getAbsolutePath();
                    saveScriptPath();
                    refreshFileList();
                }
            });
            spring(row);
        }
    }

    List<FsEntry> rootList(String str) {
        ArrayList<FsEntry> list = new ArrayList<>();
        try {
            Res res = suExec("ls -Ap --color=never " + shq(str));
            if (res.code != 0) {
                return null;
            }
            if (res.out == null) {
                return list;
            }
            for (String s : res.out.split("\n")) {
                String trim = s.trim();
                if (!trim.isEmpty() && !trim.equals(".") && !trim.equals("..")) {
                    boolean isDir = trim.endsWith("/");
                    if (isDir) {
                        trim = trim.substring(0, trim.length() - 1);
                    }
                    if (!trim.isEmpty()) {
                        list.add(new FsEntry(trim, isDir));
                    }
                }
            }
            return list;
        } catch (Exception ignored) {
            return null;
        }
    }

    void runScriptDialog(final File file) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(6), dp(2), dp(6), dp(2));
        TextView tv = tv(file.getAbsolutePath(), 13, SUBTEXT, false);
        tv.setTypeface(Typeface.MONOSPACE);
        linearLayout.addView(tv);
        final CheckBox checkBox = new CheckBox(this);
        checkBox.setText("使用 SU 权限运行（root）");
        checkBox.setChecked(true);
        checkBox.setTextColor(TEXT);
        checkBox.setButtonTintList(ColorStateList.valueOf(-13654417));
        linearLayout.addView(checkBox);
        showCustomDialog("运行脚本 · " + file.getName(), null, linearLayout,
                "运行", "取消", true,
                () -> runScript(file, checkBox.isChecked()), null);
    }

    void runScript(final File file, final boolean useSu) {
        final boolean isElf = isElf(file);
        appendLog("[脚本] " + (useSu ? "SU" : "普通") + "运行：" + file.getAbsolutePath() + (isElf ? "（检测为二进制可执行文件，直接执行）" : ""));

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(4), dp(2), dp(4), dp(2));
        final ScrollView scrollView = new ScrollView(this);
        scrollView.setVerticalScrollBarEnabled(true);
        final TextView textView = new TextView(this);
        textView.setTextSize(13.0f);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextColor(TEXT);
        textView.setLineSpacing(dp(2), 1.0f);
        scrollView.addView(textView);
        linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, dp(300)));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(0);
        inputRow.setGravity(16);
        inputRow.setPadding(0, dp(8), 0, 0);
        final EditText editText = new EditText(this);
        editText.setHint("输入数字或内容，点「发送」");
        editText.setTextSize(13.0f);
        editText.setHintTextColor(SUBTEXT);
        editText.setTextColor(TEXT);
        editText.setSingleLine(true);
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setBackground(getDrawable(R.drawable.bg_search));
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setImeOptions(4);
        inputRow.addView(editText, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button = new Button(this);
        button.setText("发送");
        button.setTextColor(-1);
        button.setTextSize(13.0f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setBackground(round(-13654417, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(42));
        lp.setMargins(dp(8), 0, 0, 0);
        inputRow.addView(button, lp);
        linearLayout.addView(inputRow);

        final Process[] processArr = {null};
        final boolean[] done = {false};
        final long[] suPid = {-1};
        final long[] realPid = {-1};
        final boolean[] dismissed = {false};

        button.setOnClickListener(v -> {
            if (processArr[0] == null || done[0]) {
                liveAppend(textView, scrollView, "\n[进程已结束，无法发送]");
                return;
            }
            String input = editText.getText().toString();
            editText.setText("");
            if (input.trim().isEmpty()) {
                return;
            }
            try {
                processArr[0].getOutputStream().write((input + "\n").getBytes("UTF-8"));
                processArr[0].getOutputStream().flush();
                liveAppend(textView, scrollView, "> " + input);
            } catch (Exception e) {
                liveAppend(textView, scrollView, "\n[发送失败：" + e.getMessage() + "]");
            }
        });
        editText.setOnEditorActionListener((textView2, i, keyEvent) -> {
            if (i != 4) {
                return false;
            }
            button.performClick();
            return true;
        });

        final AlertDialog[] holder = new AlertDialog[1];
        final LinearLayout container = dialogContainer("运行 · " + file.getName());
        final TextView scriptTitleView = (TextView) container.getChildAt(0);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(-1, -2);
        srLp.setMargins(0, dp(10), 0, 0);
        container.addView(linearLayout, srLp);
        LinearLayout srBtnRow = dialogButtonRow("清理", null, holder);
        container.addView(srBtnRow);
        final AlertDialog dialog = showDialog(container, false);
        holder[0] = dialog;
        dialog.setOnDismissListener(d -> {
            dismissed[0] = true;
            Process p = processArr[0];
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
            }
        });
        editText.requestFocus();
        editText.postDelayed(() -> showKeyboard(editText), 200L);

        new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            boolean finished = false;
            try {
                String absolutePath = file.getParentFile() != null ? file.getParentFile().getAbsolutePath() : "/";
                String shq = shq(file.getAbsolutePath());
                String shq2 = shq(absolutePath);
                if (useSu) {
                    process = new ProcessBuilder("su", "-c",
                            "echo __LOGD_PID__=$$; magic=$(head -c4 " + shq + " 2>/dev/null | od -An -tx1 | tr -d ' \\n'); "
                                    + "if [ \"$magic\" = \"7f454c46\" ]; then cd " + shq2 + " && chmod +x " + shq
                                    + " && export LD_LIBRARY_PATH=" + shq2 + ":\\\"$LD_LIBRARY_PATH\\\" && exec " + shq
                                    + "; else cd " + shq2 + " && export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/data/local/bin"
                                    + " HOME=/data/local/tmp LANG=en_US.UTF-8 TERM=xterm && exec /system/bin/sh " + shq + "; fi")
                            .redirectErrorStream(true).start();
                } else {
                    ProcessBuilder pb;
                    if (isElf) {
                        file.setExecutable(true, false);
                        pb = new ProcessBuilder(file.getAbsolutePath());
                    } else {
                        pb = new ProcessBuilder("/system/bin/sh", file.getAbsolutePath());
                    }
                    pb.directory(file.getParentFile());
                    Map<String, String> env = pb.environment();
                    env.put("PATH", DEF_PATH);
                    env.put("HOME", "/data/local/tmp");
                    env.put("LANG", "en_US.UTF-8");
                    env.put("TERM", "xterm");
                    process = pb.redirectErrorStream(true).start();
                }
                processArr[0] = process;
                suPid[0] = pidOf(process);
                reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String readLine;
                while ((readLine = reader.readLine()) != null) {
                    if (readLine.startsWith("__LOGD_PID__=")) {
                        try {
                            realPid[0] = Long.parseLong(readLine.substring(14).trim());
                        } catch (Exception ignored) {
                        }
                    } else {
                        final String line = readLine;
                        runOnUiThread(() -> liveAppend(textView, scrollView, line));
                    }
                }
                final int exitCode = process.waitFor();
                finished = true;
                runOnUiThread(() -> {
                    done[0] = true;
                    liveAppend(textView, scrollView, "\n[执行完毕，退出码 " + exitCode + "]");
                    scriptTitleView.setText("运行结束 · " + file.getName());
                    appendLog("[脚本] 执行完毕，退出码 " + exitCode);
                });
            } catch (final Exception e) {
                final boolean wasDismissed = dismissed[0];
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    done[0] = true;
                    String str2 = wasDismissed ? "已取消（进程被关闭）" : "执行失败：" + msg;
                    liveAppend(textView, scrollView, "\n[" + str2 + "]");
                    if (!wasDismissed) {
                        appendLog("[脚本] 执行失败：" + msg);
                    }
                });
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
                if (process != null) {
                    try { process.getOutputStream().close(); } catch (Exception ignored) {}
                    try { process.getInputStream().close(); } catch (Exception ignored) {}
                    try { process.destroy(); } catch (Exception ignored) {}
                }
                processArr[0] = null;
                if (finished) {
                    runOnUiThread(() -> appendLog("[脚本] 正在清理遗留进程…"));
                    long j2 = realPid[0];
                    if (j2 <= 0) {
                        j2 = useSu ? -1L : suPid[0];
                    }
                    cleanupLeftovers(suPid[0], j2, file.getName());
                }
            }
        }).start();
    }

    void liveAppend(TextView textView, ScrollView scrollView, String str) {
        String clean = cleanAnsi(str);
        if (clean.isEmpty()) {
            return;
        }
        textView.append(clean + "\n");
        scrollView.post(() -> scrollView.fullScroll(130));
    }

    String cleanAnsi(String str) {
        if (str == null) {
            return "";
        }
        String result = str.replaceAll("\u001b\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("\u001b\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)", "");
        int length = result.length();
        while (length > 0 && result.charAt(length - 1) == '\r') {
            length--;
        }
        return result.substring(0, length);
    }

    boolean isElf(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] bArr = new byte[4];
            int read = fis.read(bArr);
            fis.close();
            return read == 4 && bArr[0] == 0x7f && bArr[1] == 69 && bArr[2] == 76 && bArr[3] == 70;
        } catch (Exception ignored) {
            return false;
        }
    }

    void showScriptResult(String str, int i, String str2) {
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        if (str2 == null || str2.isEmpty()) {
            str2 = "（无输出）";
        }
        textView.setText("退出码：" + i + "\n\n" + str2);
        textView.setTextSize(13.0f);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextColor(TEXT);
        textView.setPadding(dp(4), dp(4), dp(4), dp(4));
        scrollView.addView(textView);
        showCustomDialog(str, null, scrollView, "关闭", null, true, null, null);
    }

    // ==================== Terminal ====================

    void buildTerminal() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(6), dp(6), dp(16), dp(6));
        linearLayout.setBackground(getDrawable(R.drawable.bg_topbar));
        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(icon(R.drawable.ic_back, TEXT));
        imageView.setPadding(dp(12), dp(8), dp(14), dp(8));
        linearLayout.addView(imageView);
        linearLayout.addView(tv("脚本终端", 20, TEXT, true));
        linearLayout.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button clearBtn = mkSmallBtn("清屏", SUBTEXT);
        linearLayout.addView(clearBtn);
        this.terminalContent.addView(linearLayout);
        imageView.setOnClickListener(v -> showHome());
        clearBtn.setOnClickListener(v -> this.termOut.setText(""));

        ScrollView scrollView = new ScrollView(this);
        this.termScroll = scrollView;
        scrollView.setVerticalScrollBarEnabled(true);
        TextView textView = new TextView(this);
        this.termOut = textView;
        textView.setTextSize(13.0f);
        this.termOut.setTextColor(-2559784);
        this.termOut.setTypeface(Typeface.MONOSPACE);
        this.termOut.setLineSpacing(dp(2), 1.0f);
        this.termOut.setPadding(dp(12), dp(12), dp(12), dp(12));
        this.termOut.setBackground(round(-15723492, dp(16)));
        this.termScroll.addView(this.termOut);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        lp.setMargins(dp(14), dp(12), dp(14), dp(4));
        this.terminalContent.addView(this.termScroll, lp);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(0);
        inputRow.setGravity(16);
        inputRow.setPadding(dp(14), dp(6), dp(14), dp(14));
        EditText editText = new EditText(this);
        this.termInput = editText;
        editText.setHint("输入命令，回车执行");
        this.termInput.setTextSize(14.0f);
        this.termInput.setHintTextColor(SUBTEXT);
        this.termInput.setTextColor(TEXT);
        this.termInput.setSingleLine(true);
        this.termInput.setTypeface(Typeface.MONOSPACE);
        this.termInput.setBackground(getDrawable(R.drawable.bg_search));
        this.termInput.setPadding(dp(16), dp(10), dp(16), dp(10));
        this.termInput.setImeOptions(4);
        this.termInput.setOnEditorActionListener((textView2, i, keyEvent) -> {
            if (i != 4) {
                return false;
            }
            termSend();
            return true;
        });
        inputRow.addView(this.termInput, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button button = new Button(this);
        this.termSendBtn = button;
        button.setText("发送");
        button.setTextColor(-1);
        button.setTextSize(14.0f);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, 1);
        button.setBackground(round(-13654417, dp(14)));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(76), dp(44));
        lp2.setMargins(dp(10), 0, 0, 0);
        inputRow.addView(this.termSendBtn, lp2);
        this.terminalContent.addView(inputRow);
        this.termSendBtn.setOnClickListener(v -> termSend());
    }

    void showKeyboard(View view) {
        try {
            ((InputMethodManager) getSystemService("input_method")).showSoftInput(view, 1);
        } catch (Exception ignored) {
        }
    }

    void hideKeyboard(View view) {
        try {
            ((InputMethodManager) getSystemService("input_method")).hideSoftInputFromWindow(view.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    void termStart() {
        if (this.termProc != null) {
            return;
        }
        appendTerm("$ 正在启动终端…\n");
        new Thread(() -> {
            try {
                ProcessBuilder pb;
                if (this.rootGranted) {
                    pb = new ProcessBuilder("su", "-c",
                            "export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/data/local/bin"
                                    + " HOME=/data/local/tmp LANG=en_US.UTF-8 TERM=xterm && sh");
                } else {
                    pb = new ProcessBuilder("/system/bin/sh");
                    Map<String, String> env = pb.environment();
                    env.put("PATH", DEF_PATH);
                    env.put("HOME", "/data/local/tmp");
                    env.put("LANG", "en_US.UTF-8");
                    env.put("TERM", "xterm");
                }
                pb.redirectErrorStream(true);
                Process start = pb.start();
                this.termProc = start;
                this.termIn = new BufferedWriter(new OutputStreamWriter(start.getOutputStream()));
                appendTerm(this.rootGranted ? "$ root@android:/ # 就绪（root shell），可直接输入命令\n" : "$ app@android:/ $ 就绪（普通 shell）\n");
                final String pending = this.pendingRunScript;
                this.pendingRunScript = null;
                if (pending != null) {
                    this.termInput.postDelayed(() -> termWrite(termScriptCmd(pending)), 400L);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(start.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String l = line;
                    runOnUiThread(() -> appendTerm(l + "\n"));
                }
                this.termProc = null;
                this.termIn = null;
                runOnUiThread(() -> appendTerm("$ 终端已退出\n"));
            } catch (final Exception e) {
                runOnUiThread(() -> appendTerm("[错误] 启动终端失败：" + e.getMessage() + "\n"));
                this.termProc = null;
                this.termIn = null;
            }
        }).start();
    }

    void appendTerm(final String str) {
        runOnUiThread(() -> {
            this.termOut.append(cleanAnsi(str));
            this.termScroll.post(() -> this.termScroll.fullScroll(130));
        });
    }

    void termWrite(String str) {
        if (this.termProc == null || this.termIn == null) {
            appendTerm("[错误] 终端未运行\n");
            return;
        }
        appendTerm("$ " + str + "\n");
        try {
            this.termIn.write(str + "\n");
            this.termIn.flush();
        } catch (Exception e) {
            appendTerm("[错误] 写入失败：" + e.getMessage() + "\n");
        }
    }

    void termSend() {
        String input = this.termInput.getText().toString();
        this.termInput.setText("");
        if (input.trim().isEmpty()) {
            return;
        }
        termWrite(input);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogTail();
        this.serviceRedirectMode = false;
        Process process = this.termProc;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            this.termProc = null;
            this.termIn = null;
        }
    }

    // ==================== Binary & Utilities ====================

    void ensureBinaryAsync() {
        new Thread(() -> {
            try {
                ensureBinary();
            } catch (IOException ignored) {
            }
        }).start();
    }

    File ensureBinary() throws IOException {
        File file = new File(getFilesDir(), EXE_NAME);
        InputStream open = getAssets().open(ASSET);
        FileOutputStream fos = new FileOutputStream(file);
        byte[] bArr = new byte[16384];
        int read;
        while ((read = open.read(bArr)) > 0) {
            fos.write(bArr, 0, read);
        }
        fos.close();
        open.close();
        file.setExecutable(true, false);
        return file;
    }

    String shq(String str) {
        return "'" + str.replace("'", "'\\''") + "'";
    }

    static long pidOf(Process process) {
        if (process == null) {
            return -1L;
        }
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            try {
                return f.getLong(process);
            } catch (IllegalArgumentException e) {
                return f.getInt(process);
            }
        } catch (Exception e) {
            return -1L;
        }
    }

    void cleanupLeftovers(long suPid, long realPid, String name) {
        try {
            if (!this.rootGranted) {
                appendLog("[脚本] 已清理所有遗留进程（无 root，跳过）");
                return;
            }
            StringBuilder killList = new StringBuilder();
            StringBuilder logList = new StringBuilder();
            if (suPid > 0 && suPid != realPid) {
                killList.append(suPid);
                killList.append(' ');
                logList.append(" PID=").append(suPid).append("（su）");
            }
            if (realPid > 0) {
                killList.append(realPid);
                killList.append(' ');
                if (name == null || name.isEmpty()) {
                    name = "sh";
                }
                logList.append(" PID=").append(realPid).append("（").append(name).append("）");
            }
            String killStr = killList.toString().trim();
            String myPid = String.valueOf(android.os.Process.myPid());
            Res res = suExec("UIDSEG=$(grep -o '/uid_[0-9]*' /proc/self/cgroup | head -1); [ -z \"$UIDSEG\" ] && exit 0; "
                    + (killStr.isEmpty() ? "" : "kill -9 " + killStr + " 2>/dev/null; ")
                    + "for p in /proc/[0-9]*; do pid=${p#/proc/}; [ \"$pid\" = \"" + myPid + "\" ] && continue; "
                    + "[ \"$pid\" = \"$$\" ] && continue; [ \"$pid\" = \"$PPID\" ] && continue; "
                    + "case \" " + killStr + " \" in *\" $pid \"*) continue;; esac; "
                    + "cg=$(cat \"$p/cgroup\" 2>/dev/null) || continue; case \"$cg\" in *\"$UIDSEG\"*) ;; *) continue;; esac; "
                    + "name=$(cat \"$p/comm\" 2>/dev/null); echo \"$pid $name\"; kill -9 \"$pid\" 2>/dev/null; done");
            if (res.out != null) {
                for (String s : res.out.split("\n")) {
                    String trim = s.trim();
                    if (!trim.isEmpty()) {
                        String[] split = trim.split("\\s+", 2);
                        logList.append(" PID=").append(split[0]);
                        if (split.length > 1 && !split[1].isEmpty()) {
                            logList.append("（").append(split[1].trim()).append("）");
                        }
                    }
                }
            }
            if (logList.length() == 0) {
                appendLog("[脚本] 已清理所有遗留进程（无残留）");
                return;
            }
            appendLog("[脚本] 已清理所有遗留进程" + logList);
        } catch (Exception e) {
            appendLog("[脚本] 清理遗留进程失败：" + e.getMessage());
        }
    }

    Res suExec(String str) throws Exception {
        Process start = new ProcessBuilder("su", "-c", str).redirectErrorStream(true).start();
        return new Res(start.waitFor(), read(start.getInputStream()));
    }

    void runRoot(final String cmd, final String msg) {
        new Thread(() -> {
            try {
                Res res = suExec(cmd);
                String log = res.code == 0 ? "[系统] " + msg : "操作失败：" + res.out.trim();
                appendLog(log);
            } catch (Exception e) {
                appendLog("Root 执行失败：" + e.getMessage());
            }
        }).start();
    }

    static String read(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
            sb.append('\n');
        }
        return sb.toString();
    }
}
