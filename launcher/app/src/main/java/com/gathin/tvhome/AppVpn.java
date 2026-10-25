package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Arrays;

/**
 * Turns the VPN app on before opening the apps in config.xml vpn_for_apps (YouTube), and off again when Home comes
 * back, if it was this launcher that turned it on. The VPN app is driven by its own broadcast receiver
 * (vpn_connect_receiver with the actions vpn_connect_action / vpn_disconnect_action; Incy:
 * llc.itdev.incy/.receiver.VpnIntentReceiver, llc.itdev.incy.CONNECT and DISCONNECT). The app needs Android's VPN
 * permission, given once in the app itself; without it the app stays off and the target opens after the timeout.
 */
final class AppVpn {
    private static final long WAIT_MS = 8000;
    private static final String PREFS = "app_vpn", KEY_OURS = "connected_by_home";

    private AppVpn() { }

    /** Starts launch, first bringing the VPN up when pkg is listed and no VPN is up yet. */
    static void start(Context ctx, String pkg, Intent launch) {
        Context app = ctx.getApplicationContext();
        if (!wanted(app, pkg) || vpnUp(app)) {
            open(ctx, launch);
            return;
        }
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] done = {false};
        ConnectivityManager.NetworkCallback[] callback = new ConnectivityManager.NetworkCallback[1];
        Runnable go = () -> {
            if (done[0]) return;
            done[0] = true;
            try { cm.unregisterNetworkCallback(callback[0]); } catch (RuntimeException ignored) { }
            if (!vpnUp(app)) Toast.makeText(app, R.string.vpn_not_connected, Toast.LENGTH_SHORT).show();
            open(ctx, launch);
        };
        callback[0] = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.post(go); }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), callback[0]);
        } catch (RuntimeException e) {
            Log.w("TvHome.Vpn", "Could not watch for the VPN", e);
        }
        Toast.makeText(app, R.string.vpn_connecting, Toast.LENGTH_SHORT).show();
        if (send(app, R.string.vpn_connect_action)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OURS, true).apply();
        }
        main.postDelayed(go, WAIT_MS);
    }

    private static long lastEnsure;

    /**
     * vpn_keep_on: Home came up (the TV was switched on, or back from an app) and the VPN is down, so bring it up
     * again. For a VPN app that routes only some apps (Incy set to YouTube alone), staying on costs nothing elsewhere.
     */
    static void ensureUp(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!enabled(app) || !app.getResources().getBoolean(R.bool.vpn_keep_on)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastEnsure < 60_000 && lastEnsure != 0) return;
        lastEnsure = now;
        if (vpnUp(app)) return;
        send(app, R.string.vpn_connect_action);
        // Just after power-on the network can still be coming up, and the first try fails: try once more.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!vpnUp(app)) send(app, R.string.vpn_connect_action);
        }, 20_000);
    }

    /** Home is back: turn the VPN off again if this launcher turned it on. */
    static void releaseIfOurs(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (app.getResources().getBoolean(R.bool.vpn_keep_on)) return;
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OURS, false)) return;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OURS, false).apply();
        if (vpnUp(app)) send(app, R.string.vpn_disconnect_action);
    }

    /** Packages whose windows come and go around a VPN app without leaving YouTube: dialogs, the keyboard. */
    private static final java.util.Set<String> PASS_THROUGH = new java.util.HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.vpndialogs", "com.tcl.inputmethod.international",
            "com.google.android.inputmethod.latin", "com.google.android.katniss"));

    static boolean enabled(Context ctx) {
        return !ctx.getString(R.string.vpn_connect_receiver).isEmpty();
    }

    /**
     * A window of pkg came to the front (HomeKeyService). Leaving the VPN apps for anything else turns a VPN this
     * launcher started off again; Home does that itself in onResume.
     */
    static void onForeground(Context ctx, String pkg) {
        if (pkg.equals(ctx.getPackageName()) || PASS_THROUGH.contains(pkg) || wanted(ctx, pkg)) return;
        ComponentName receiver = ComponentName.unflattenFromString(ctx.getString(R.string.vpn_connect_receiver));
        if (receiver != null && pkg.equals(receiver.getPackageName())) return;
        releaseIfOurs(ctx);
    }

    private static boolean wanted(Context ctx, String pkg) {
        if (pkg == null || ctx.getString(R.string.vpn_connect_receiver).isEmpty()) return false;
        return Arrays.asList(ctx.getResources().getStringArray(R.array.vpn_for_apps)).contains(pkg);
    }

    static boolean vpnUp(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static boolean send(Context ctx, int action) {
        ComponentName receiver = ComponentName.unflattenFromString(ctx.getString(R.string.vpn_connect_receiver));
        if (receiver == null) return false;
        try {
            ctx.sendBroadcast(new Intent(ctx.getString(action)).setComponent(receiver)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            return true;
        } catch (RuntimeException e) {
            Log.w("TvHome.Vpn", "Could not reach the VPN app", e);
            return false;
        }
    }

    private static void open(Context ctx, Intent launch) {
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w("TvHome.Vpn", "Could not open " + launch, e);
        }
    }
}
