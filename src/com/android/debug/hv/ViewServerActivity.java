package com.android.debug.hv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

public class ViewServerActivity extends Activity {
	private int mCounter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (getIntent().getExtras() != null) mCounter = getIntent().getExtras().getInt("counter");
        ((TextView) findViewById(R.id.label)).setText("Activity #" + (mCounter + 1));
        
        String s = Uri.decode("action://LaunchScreen/SubMenuScreen?menuId=com.tomtom.navui.stocknavapp%2Fsettingssubmenu&redirectUri=SubMenuScreen?menuId=com.tomtom.navui.stocknavapp%2Fsettingssubmenu%26redirectUri=SubMenuScreen");
        s = Uri.decode(s);
        Log.d("DUPAA2", s);
        
//        final Resources resources = this.getResources();
//        resources.getIdentifier(name, defType, defPackage)
//        if (id >= 0) {
//            try {
//                fieldValue = resources.getResourceTypeName(id) + '/' +
//                        resources.getResourceEntryName(id);
//            }
        ViewServer.get(this).addWindow(this);
    }
    
    public void nextActivity(View v) {
    	Intent intent = new Intent(this, getClass());
    	intent.putExtra("counter", mCounter + 1);
		startActivity(intent);
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	ViewServer.get(this).removeWindow(this);
    }
    
    @Override
    public void onResume() {
    	super.onResume();
    	ViewServer.get(this).setFocusedWindow(this);
    }
}