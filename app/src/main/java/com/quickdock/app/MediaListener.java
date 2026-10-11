package com.quickdock.app;

import android.service.notification.NotificationListenerService;

/**
 * خدمة فارغة؛ وجودها (مع منح «الوصول إلى الإشعارات») هو ما يسمح لودجت الوسائط بقراءة جلسات التشغيل والتحكم بها.
 * تُمنح تلقائياً من صفحة التثبيت: cmd notification allow_listener com.quickdock.app/com.quickdock.app.MediaListener
 */
public class MediaListener extends NotificationListenerService { }
