package com.android.debug.hv;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

public class ReflectionUtils {

    private static final String TAG = "ReflectionUtils";

    private ReflectionUtils() {
    }

    public static void setField( String fieldName, Object value, View vv ) throws NoSuchFieldException, IllegalAccessException {
        Field field = vv.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(vv, value);
    }

    public static void displayFields() {
        Field[] fields = TextView.class.getDeclaredFields();
        Log.d(TAG, "fileds " + fields.length);
        for (Field field : fields) {
            Log.d(TAG, "field " + field.getModifiers() + "  " + field.getType() + "  " + field.getName() + " ");
        }
    }

    public static void displayMethods( Object vv ) {
        if (vv == null){
            return;
        }
        Method[] methods = vv.getClass().getMethods();
        Log.d(TAG, "methods " + methods.length);
        for (Method method : methods) {
            Log.d(TAG, "method " + method.getModifiers() + "  " + method.getReturnType() + "  " + method.getName() + " ");
        }
    }
}
