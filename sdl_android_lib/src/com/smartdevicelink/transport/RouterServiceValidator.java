package com.smartdevicelink.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.smartdevicelink.util.HttpRequestTask;
import com.smartdevicelink.util.HttpRequestTask.HttpRequestTaskCallback;

/**
 * This class will tell us if the currently running router service is valid or not.
 * To use this class simply create a new instance of RouterServiceValidator with a supplied context.
 * After that, you have the option to set if you want to test in a production setting. If not, it will default to a debug setting.
 * Once you are ready to check if the router service is trusted you simply call routerServiceValidator.validate();
 * <br><br> This validator should be passed into the multiplexing transport construction as well.
 * @author Joey Grover
 *
 */
public class RouterServiceValidator {
	private static final String TAG = "PackageCheckUtl";
	public static final String ROUTER_SERVICE_PACKAGE = "com.sdl.router";

	private static final String REQUEST_PREFIX = "https://woprjr.smartdevicelink.org/api/1/applications/queryTrustedRouters"; 

	private static final String DEFAULT_APP_LIST = "{\"response\": {\"com.livio.sdl\" : { \"versionBlacklist\":[] }, \"com.lexus.tcapp\" : { \"versionBlacklist\":[] }, \"com.toyota.tcapp\" : { \"versionBlacklist\": [] } , \"com.sdl.router\":{\"versionBlacklist\": [] } }}"; 
	
	
	private static final String JSON_RESPONSE_OBJECT_TAG = "response";
	private static final String JSON_RESONSE_APP_VERSIONS_TAG = "versionBlacklist";

	private static final String JSON_PUT_ARRAY_TAG = "installedApps";
	private static final String JSON_APP_PACKAGE_TAG = "packageName";
	private static final String JSON_APP_VERSION_TAG = "version";

	
	private static final long REFRESH_TRUSTED_APP_LIST_TIME 	= 3600000 * 24; // 24 hours in ms
	
	private static final String SDL = "sdl";
	private static final String SDL_PACKAGE_LIST = "sdl_package_list";
	private static final String SDL_PACKAGE_LIST_TIMESTAMP = "sdl_package_list_timestamp";

	//Flags to aid in debugging and production checks
	public static final int FLAG_DEBUG_NONE 				= 0x00;
	public static final int FLAG_DEBUG_PACKAGE_CHECK 		= 0x01;
	/**
	 * This will flag the validator to check for app version during debugging. 
	 * <br><br><b>NOTE: This flag will include a package check as well.
	 */
	public static final int FLAG_DEBUG_VERSION_CHECK 		= 0x03; //We use 3 becuase version check will be 2, but since a version check implies a package check we do 2+1=3;
	public static final int FLAG_DEBUG_INSTALLED_FROM_CHECK = 0x04;
	public static final int FLAG_DEBUG_USE_TIMESTAMP_CHECK = 0x05;

	public static final int FLAG_DEBUG_PERFORM_ALL_CHECKS 	= 0xFF;
	
	
	private int flags = FLAG_DEBUG_NONE;

	private Context context= null;
	private boolean inDebugMode = false;
	private static boolean pendingListRefresh = false;
	
	private ComponentName service;//This is how we can save different routers over another in a waterfall method if we choose to.

	
	public RouterServiceValidator(Context context){
		this.context = context;
		inDebugMode = inDebugMode();
	}
	
	public RouterServiceValidator(Context context, ComponentName service){
		this.context = context;
		inDebugMode = inDebugMode();
		this.service = service;
	}
	/**
	 * Main function to call to ensure we are connecting to a validated router service
	 * @return whether or not the currently running router service can be trusted.
	 */
	public boolean validate(){
		PackageManager pm = context.getPackageManager();
		//Grab the package for the currently running router service. We need this call regardless of if we are in debug mode or not.
		String packageName = null;
		
		if(this.service != null){
			Log.d(TAG, "Supplied service name of " + this.service.getClassName());
			if(!isServiceRunning(context,this.service)){
				//This means our service isn't actually running, so set to null. Hopefully we can find a real router service after this.
				service = null;
				Log.w(TAG, "Supplied service is not actually running.");
			}
		}
		if(this.service == null){
			this.service= componentNameForServiceRunning(pm); //Change this to an array if multiple services are started?
			if(this.service == null){ //if this is still null we know there is no service running so we can return false
				wakeUpRouterServices();
				return false;
			}
		}
		
		Log.d(TAG, "Checking app package: " + service.getClassName());
		packageName = this.appPackageForComponentName(service, pm);
		

		if(packageName!=null){//Make sure there is a service running
			if(wasInstalledByAppStore(packageName)){ //Was this package installed from a trusted app store
				if( isTrustedPackage(packageName, pm)){//Is this package on the list of trusted apps.
					return true;
				}
			}
		}//No running service found. Might need to attempt to start one
		//TODO spin up a known good router service
		
		if(context.getPackageName().equalsIgnoreCase(packageName)){
			Log.d(TAG, "It's our router service running, so time to shut it down");
			Intent intent = new Intent();
			intent.setComponent(service);
			//TODO Have to fix logic when router service stops. What is the correcty flow?
			try{context.stopService(intent);}catch(Exception e){}
		}
		wakeUpRouterServices();
		return false;
	}

	/**
	 * This will ensure that all router services are aware that there are no valid router services running and should start up 
	 */
	private void wakeUpRouterServices(){
		if(BluetoothAdapter.getDefaultAdapter()!=null && BluetoothAdapter.getDefaultAdapter().isEnabled()){
			Intent intent = new Intent(TransportConstants.START_ROUTER_SERVICE_ACTION);
			intent.putExtra(TransportConstants.PING_ROUTER_SERVICE_EXTRA, true);
			context.sendBroadcast(intent);
		}
	}
	public ComponentName getService(){
		return this.service;
	}

	private boolean shouldOverrideVersionCheck(){
		return (this.inDebugMode && ((this.flags & FLAG_DEBUG_VERSION_CHECK) != FLAG_DEBUG_VERSION_CHECK));
	}
	
	private boolean shouldOverridePackageName(){
		return (this.inDebugMode && ((this.flags & FLAG_DEBUG_PACKAGE_CHECK) != FLAG_DEBUG_PACKAGE_CHECK));
	}
	
	private boolean shouldOverrideInstalledFrom(){
		return (this.inDebugMode && ((this.flags & FLAG_DEBUG_INSTALLED_FROM_CHECK) != FLAG_DEBUG_INSTALLED_FROM_CHECK));
	}
	
	private boolean shouldOverrideTimeCheck(){
		return (this.inDebugMode && ((this.flags & FLAG_DEBUG_USE_TIMESTAMP_CHECK) != FLAG_DEBUG_USE_TIMESTAMP_CHECK));
	}
	
	
	/**
	 *  Use this method if you would like to test your app in a production setting rather than defaulting to a
	 * debug mode where you connect to whatever router service is running.
	 * <br><br><b>These flags are only used in debugging mode. During production they will be ignored.</b>
	 * @param flags
	 */
	public void setFlags(int flags){
		this.flags = flags;
	}
	
	/**
	 * This method will find which router service is running. Use that info to find out more about that app and service.
	 * It will store the found service for later use and return the package name if found. 
	 * @param context
	 * @return
	 */
	public ComponentName componentNameForServiceRunning(PackageManager pm){
		if(context==null){
			return null;
		}
		ActivityManager manager = (ActivityManager) context.getSystemService("activity");
		//PackageManager pm = context.getPackageManager();
		
		
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			//Log.d(TAG, service.service.getClassName());
			//We will check to see if it contains this name, should be pretty specific
			if ((service.service.getClassName()).toLowerCase(Locale.US).contains(SdlBroadcastReceiver.SDL_ROUTER_SERVICE_CLASS_NAME)){ 
				//this.service = service.service; //This is great
				if(service.started && service.restarting==0){ //If this service has been started and is not crashed
					return service.service; //appPackageForComponenetName(service.service,pm);
				}
			}
		}			

		return null;
	}
	
	/**
	 * Returns the package name for the component name
	 * @param cn
	 * @param pm
	 * @return
	 */
	private String appPackageForComponentName(ComponentName cn,PackageManager pm ){
		if(cn!=null && pm!=null){
			ServiceInfo info;
			try {
				info = pm.getServiceInfo(cn, 0);
				return info.applicationInfo.packageName;
			} catch (NameNotFoundException e) {
				e.printStackTrace();
			}
		}
		return null;
		
	}
	
	/**
	 * Check to see if the app was installed from a trusted app store.
	 * @param packageName the package name of the app to be tested
	 * @return whether or not the app was installed from a trusted app store
	 */
	public boolean wasInstalledByAppStore(String packageName){
		if(shouldOverrideInstalledFrom()){
			return true;
		}
		PackageManager packageManager = context.getPackageManager();
		try {
			final ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
			//TODO might want to try to get version with this packageManager.getPackageInfo(packageName, 0).versionCode;
			if ("com.android.vending".equals(packageManager.getInstallerPackageName(applicationInfo.packageName))
					|| "com.amazon.venezia".equals(packageManager.getInstallerPackageName(applicationInfo.packageName))) {
				// App was installed by Play Store
				return true;
			}
		} catch (final NameNotFoundException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	/**
	 * This method will check to see if this app is a debug build. If it is, we will attempt to connect to any router service.
	 * If false, it will only connect to approved apps with router services.
	 * @return
	 */
	public boolean inDebugMode(){
		return (0 != (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
	}
	
	
	private boolean isTrustedPackage(String packageName, PackageManager pm){
		if(packageName == null){
			return false;
		}
		
		if(shouldOverridePackageName()){ //If we don't care about package names, just return true;
			return true;
		}

		int version = -1;
		try {version = pm.getPackageInfo(packageName,0).versionCode;} catch (NameNotFoundException e1) {e1.printStackTrace(); return false;}
		
		JSONObject trustedApps = stringToJson(getTrustedList(context));
		JSONArray versions;
		JSONObject app = null;

		try {
			app = trustedApps.getJSONObject(packageName);
		} catch (JSONException e) {
			e.printStackTrace();
			return false;
		} 
		
		if(app!=null){
			//At this point, an app object was found in the JSON list that matches the package name
			if(shouldOverrideVersionCheck()){ //If we don't care about versions, just return true
				return true;
			}
			try { versions = app.getJSONArray(JSON_RESONSE_APP_VERSIONS_TAG); } catch (JSONException e) {	e.printStackTrace();return false;}
			return verifyVersion(version, versions);
		}
		
		return false;
	}
	
	protected boolean verifyVersion(int version, JSONArray versions){
		if(version<0){
			return false;
		}
		if(versions == null || versions.length()==0){
			return true;
		}
		for(int i=0;i<versions.length();i++){
			try {
				if(version == versions.getInt(i)){
					return false;
				}
			} catch (JSONException e) {
				continue;
			}
		}//We didn't find our version in the black list.
		return true;
	}
	
	/**
	 * Using the knowledge that all SDL enabled apps have an SDL Broadcast Receiver that has an intent filter that includes a specific 
	 * intent. 
	 * @return 
	 */
	private static List<SdlApp> findAllSdlApps(Context context){
		List<SdlApp> apps = new ArrayList<SdlApp>();
		PackageManager packageManager = context.getPackageManager();
		Intent intent = new Intent();
		intent.setAction("sdl.router.startservice");			//FIXME change this string in both the docs and the code
		List<ResolveInfo> infoList = packageManager.queryBroadcastReceivers(intent, 0);
		if(infoList!=null){
			Log.i(TAG, "Number of SDL apps: " + infoList.size());
			String packageName;
			for(ResolveInfo info : infoList){
				Log.i(TAG, "SDL apps: " + info.activityInfo.packageName);
				packageName = info.activityInfo.packageName;
				try {
					apps.add(new SdlApp(packageName,packageManager.getPackageInfo(packageName,0).versionCode));
				} catch (NameNotFoundException e) {
					e.printStackTrace();
				}
			}
			
			return apps;
		}else{
			Log.i(TAG, "No SDL apps, list was null");
			return null;
		}
	}
	
	/**
	 * Performs a look up against installed SDL apps that support the router service.
	 * When it receives a list back from the server it will store it for later use.
	 * @param context
	 */
	public static boolean createTrustedListRequest(final Context context, boolean forceRefresh){
		return createTrustedListRequest(context,forceRefresh,null);
	}
	
	protected static boolean createTrustedListRequest(final Context context, boolean forceRefresh,HttpRequestTask.HttpRequestTaskCallback cb ){
		if(context == null){
			return false;
		}
		
		if(!forceRefresh && (System.currentTimeMillis()-getTrustedAppListTimeStamp(context))<REFRESH_TRUSTED_APP_LIST_TIME){ //FIXME
			Log.d(TAG, "Don't need to get new list");
			//Our list should still be ok for now so we will skip the request
			pendingListRefresh = false;
			return false;
		}
		
		pendingListRefresh = true;
		//Might want to store a flag letting this class know a request is currently pending
		StringBuilder builder = new StringBuilder();
		builder.append(REQUEST_PREFIX);
		
		List<SdlApp> apps = findAllSdlApps(context);
		
		JSONObject object = new JSONObject();
		JSONArray array = new JSONArray();
		JSONObject jsonApp;
		
		for(SdlApp app: apps){	//Format all the apps into a JSON object and add it to the JSON array
			try{
				jsonApp = new JSONObject();
				jsonApp.put(JSON_APP_PACKAGE_TAG, app.packageName);
				jsonApp.put(JSON_APP_VERSION_TAG, app.versionCode);
				array.put(jsonApp);
			}catch(JSONException e){
				e.printStackTrace();
				continue;
			}
		}
		
		try {object.put(JSON_PUT_ARRAY_TAG, array);} catch (JSONException e) {e.printStackTrace();}
		
		Log.d(TAG, "Request of apps: " + object.toString());

		if (cb == null) {
			cb = new HttpRequestTaskCallback() {

				@Override
				public void httpCallComplete(String response) {
					// Might want to check if this list is ok
					Log.d(TAG, "APPS! " + response);
					setTrustedList(context, response);
					pendingListRefresh = false;
				}

				@Override
				public void httpFailure(int statusCode) {
					Log.e(TAG, "Error while requesting trusted app list: "
							+ statusCode);
					pendingListRefresh = false;
				}
			};
		}

		new HttpRequestTask(cb).execute(REQUEST_PREFIX,HttpRequestTask.REQUEST_TYPE_POST,object.toString(),"application/json","application/json");
		
		return true;
	}
	
	/**
	 * This method will determine if our supplied component name is really running. 
	 * @param context
	 * @param service
	 * @return
	 */
	protected boolean isServiceRunning(Context context, ComponentName service){
		 ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		    for (RunningServiceInfo serviceInfo : manager.getRunningServices(Integer.MAX_VALUE)) {
		        if (serviceInfo.service.equals(service)) {
		            return true;
		        }
		    }
		return false;
	}
	
	
	/**
	 * Parses a string into a JSON array
	 * @param json
	 * @return
	 */
	protected JSONObject stringToJson(String json){
		if(json==null){
			return stringToJson(DEFAULT_APP_LIST);
		}
		try {//FIXME this isn't complete. Once SHAID or other service is POC'd we can adjust
			JSONObject object = new JSONObject(json);
			JSONObject trustedApps = object.getJSONObject(JSON_RESPONSE_OBJECT_TAG);
			return trustedApps;
			
		} catch (JSONException e) {
			e.printStackTrace();
			if(!json.equalsIgnoreCase(DEFAULT_APP_LIST)){ //Since we were unable to parse, let's fall back to at least our last known good list. If this list is somehow messed up, just quit.
				return stringToJson(DEFAULT_APP_LIST);
			}else{
				return null;
			}
		}		
	}
	
	public static boolean invalidateList(Context context){
		if(context == null){
			return false;
		}
		SharedPreferences pref = context.getSharedPreferences(SDL, Context.MODE_PRIVATE);
		// Write the new prefs
		SharedPreferences.Editor prefAdd = pref.edit();
		prefAdd.putLong(SDL_PACKAGE_LIST_TIMESTAMP, 0); //This will be the last time we updated
		return prefAdd.commit();
	}
	/******************************************************************
	 * 
	 * Saving the list for later!!!
	 * 
	 ******************************************************************/

	/**
	 * Saves the list of available applications into user's shared prefs.
	 * @param context The application's environment
	 * @param jsonString The JSON string to save.
	 */
	protected static boolean setTrustedList(Context context, String jsonString){
		if(jsonString!=null && context!=null){
			SharedPreferences pref = context.getSharedPreferences(SDL, Context.MODE_PRIVATE);
			// Write the new prefs
    		SharedPreferences.Editor prefAdd = pref.edit();
    		prefAdd.putString(SDL_PACKAGE_LIST, jsonString);
    		prefAdd.putLong(SDL_PACKAGE_LIST_TIMESTAMP, System.currentTimeMillis()); //This will be the last time we updated
    		return prefAdd.commit();
		}
		return false;
	}

	/**
	 * Retrieves the list of available applications from user's shared prefs.
	 * @param context The application's environment.
	 * @return The JSON string that was retrieved.
	 */
	protected static String getTrustedList(Context context){
			if(context!=null){
				SharedPreferences pref = context.getSharedPreferences(SDL, Context.MODE_PRIVATE);
				return pref.getString(SDL_PACKAGE_LIST, DEFAULT_APP_LIST);
			}
			return null;
	}
	
	/**
	 * Retrieves the time stamp from the user's shared prefs.
	 * @param context The application's environment.
	 * @return The time stamp that was retrieved.
	 */
	protected static Long getTrustedAppListTimeStamp(Context context){
		if(context!=null){
			SharedPreferences pref = context.getSharedPreferences(SDL, Context.MODE_PRIVATE);
			return pref.getLong(SDL_PACKAGE_LIST_TIMESTAMP, 0);
		}
		return -1L;
	}

	
	
	/**
	 * Class that holds all the info we want to send/receive from the validation server
	 */
	public static class SdlApp{
		String packageName;
		int versionCode;
		
		SdlApp(String packageName, int versionCode){
			this.packageName = packageName;
			this.versionCode = versionCode;
		}
	}
	
	

}