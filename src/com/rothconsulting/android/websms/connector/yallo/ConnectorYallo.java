/*
 * Copyright (C) 2010 Koni
 * 
 * This file is only usefull as part of WebSMS.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package com.rothconsulting.android.websms.connector.yallo;

import java.net.HttpURLConnection;
import java.util.ArrayList;

import org.apache.http.HttpResponse;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.message.BasicNameValuePair;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.analytics.tracking.android.Fields;
import com.google.analytics.tracking.android.GoogleAnalytics;
import com.google.analytics.tracking.android.MapBuilder;
import com.google.analytics.tracking.android.Tracker;

import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.Utils.HttpOptions;
import de.ub0r.android.websms.connector.common.WebSMSException;

//import android.util.Log;

/**
 * AsyncTask to manage IO to Yallo API.
 * 
 * @author koni
 */
public class ConnectorYallo extends Connector {
	/** Tag for output. */
	private static final String TAG = "Yallo";

	/** Login URL. */
	private static final String URL_LOGIN = "https://www.yallo.ch/kp/dyn/web/j_security_check.do";
	/** SMS URL. */
	private static final String URL_SENDSMS = "https://www.yallo.ch/kp/dyn/web/sec/acc/sms/sendSms.do";

	private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.10; rv:35.0) Gecko/20100101 Firefox/35.0";

	private static final String YALLO_ENCODING = "ISO-8859-1";

	private static final String DUMMY = "???";
	private String GUTHABEN_CHF = DUMMY;
	private String GUTHABEN_SMS_GRATIS = DUMMY;
	private String GUTHABEN_SMS_BEZAHLT = DUMMY;

	/** Check whether this connector is bootstrapping. */
	private static boolean inBootstrap = false;
	/** My Ad-ID */
	private static final String AD_UNITID = "ca-app-pub-5619114666968507/9953800139";
	/** My Analytics-ID */
	private static final String ANALYTICS_ID = "UA-38114228-3";

	private Tracker mGaTracker;
	private GoogleAnalytics mGaInstance;

	private void initAnalytics(final Context context) {
		// Get singleton.
		this.mGaInstance = GoogleAnalytics.getInstance(context);
		// To set the default tracker, use:
		// First get a tracker using a new property ID.
		Tracker newTracker = this.mGaInstance.getTracker(ANALYTICS_ID);
		// Then make newTracker the default tracker globally.
		this.mGaInstance.setDefaultTracker(newTracker);
		// Get default tracker.
		this.mGaTracker = this.mGaInstance.getDefaultTracker();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec initSpec(final Context context) {
		final String name = context.getString(R.string.connector_yallo_name);
		ConnectorSpec c = new ConnectorSpec(name);
		c.setAuthor(context.getString(R.string.connector_yallo_author));
		c.setBalance(null);
		c.setLimitLength(130);
		c.setAdUnitId(AD_UNITID);
		c.setCapabilities(ConnectorSpec.CAPABILITIES_BOOTSTRAP | ConnectorSpec.CAPABILITIES_UPDATE
				| ConnectorSpec.CAPABILITIES_SEND | ConnectorSpec.CAPABILITIES_PREFS);
		c.addSubConnector("yallo", c.getName(), SubConnectorSpec.FEATURE_MULTIRECIPIENTS);
		return c;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec updateSpec(final Context context, final ConnectorSpec connectorSpec) {
		this.log("Start updateSpec");
		final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		if (p.getBoolean(Preferences.PREFS_ENABLED, false)) {
			if (p.getString(Preferences.PREFS_USER, "").length() > 0 && p.getString(Preferences.PREFS_PASSWORD, "") // .
					.length() > 0) {
				connectorSpec.setReady();
			} else {
				connectorSpec.setStatus(ConnectorSpec.STATUS_ENABLED);
			}
		} else {
			connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
		}
		this.log("End updateSpec");
		return connectorSpec;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doBootstrap(final Context context, final Intent intent) throws WebSMSException {
		this.log("Start doBootstrap");
		if (inBootstrap && !this.GUTHABEN_SMS_GRATIS.equals(DUMMY)) {
			this.log("already in bootstrap: skip bootstrap");
			return;
		}
		inBootstrap = true;

		final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		// Building POST parameter
		ArrayList<BasicNameValuePair> postParameter = new ArrayList<BasicNameValuePair>();
		postParameter.add(new BasicNameValuePair("j_username", p.getString(Preferences.PREFS_USER, "")));
		postParameter.add(new BasicNameValuePair("j_password", p.getString(Preferences.PREFS_PASSWORD, "")));

		this.log("URL_LOGIN=" + URL_LOGIN);
		this.sendData(URL_LOGIN, context, postParameter, false);
		this.log("End doBootstrap");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doUpdate(final Context context, final Intent intent) throws WebSMSException {
		this.log("Start doUpdate");
		this.doBootstrap(context, intent);

		this.sendData(URL_SENDSMS, context, null, true);
		this.setBalance(context);

		// Google analytics
		if (this.mGaTracker == null || this.mGaInstance == null) {
			this.initAnalytics(context);
		}
		// Google analytics
		if (this.mGaTracker != null) {
			this.mGaTracker.send(MapBuilder
					.createEvent(TAG, "doUpdate V3", "Balance: " + this.getSpec(context).getBalance(), null)
					.set(Fields.SESSION_CONTROL, "start").build());
		}
		this.log("End doUpdate");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doSend(final Context context, final Intent intent) throws WebSMSException {
		this.log("Start doSend");
		this.doBootstrap(context, intent);

		ConnectorCommand command = new ConnectorCommand(intent);

		this.log("message=" + command.getText());

		String[] to = command.getRecipients();
		this.log("numer of recipients=" + to.length);

		for (int i = 0; i < to.length; i++) {
			String receiver = to[i];
			int start = receiver.indexOf("<");
			int end = receiver.indexOf(">");
			String destination = receiver.substring(start + 1, end);

			// Building POST parameter
			ArrayList<BasicNameValuePair> postParameter = new ArrayList<BasicNameValuePair>();
			postParameter.add(new BasicNameValuePair("charsLeft", "" + (130 - command.getText().length())));
			postParameter.add(new BasicNameValuePair("destination", destination));
			postParameter.add(new BasicNameValuePair("message", command.getText()));
			postParameter.add(new BasicNameValuePair("send", "senden"));

			this.log("URL_SENDSMS=" + URL_SENDSMS);
			// push data
			this.sendData(URL_SENDSMS, context, postParameter, true);

			// Google analytics
			if (this.mGaTracker == null || this.mGaInstance == null) {
				this.initAnalytics(context);
			}
			// Google analytics
			if (this.mGaTracker != null) {
				this.mGaTracker.send(MapBuilder.createEvent(TAG, "Send SMS V3", "Count receiver: " + i + 1, null)
						.set(Fields.SESSION_CONTROL, "start").build());
			}
		}
		this.log("End doSend");
	}

	/**
	 * Send data.
	 * 
	 * @param context
	 *            {@link Context}
	 * @param packetData
	 *            packetData
	 * @throws WebSMSException
	 *             WebSMSException
	 */
	private void sendData(final String url, final Context context, final ArrayList<BasicNameValuePair> postParameter,
			final boolean parseHtml) throws WebSMSException {
		this.log("Start sendData");
		try { // get Connection

			this.log(url);

			HttpOptions httpOptions = new HttpOptions();
			httpOptions.url = url;
			httpOptions.userAgent = USER_AGENT;
			httpOptions.trustAll = true;
			this.log("UrlEncodedFormEntity()");
			if (postParameter != null) {
				httpOptions.postData = new UrlEncodedFormEntity(postParameter, YALLO_ENCODING);
			}

			this.log("send data: getHttpClient(...)");
			HttpResponse response = Utils.getHttpClient(httpOptions);

			int resp = response.getStatusLine().getStatusCode();
			if (resp != HttpURLConnection.HTTP_OK) {
				throw new WebSMSException(context, R.string.error_http, "" + resp);
			}
			String htmlText = Utils.stream2str(response.getEntity().getContent()).trim();
			if (htmlText == null || htmlText.length() == 0) {
				throw new WebSMSException(context, R.string.error_service);
			}
			this.log("--HTTP RESPONSE--");
			this.log(htmlText);
			this.log("--HTTP RESPONSE--");

			if (parseHtml) {
				// Guthaben CHF
				int indexStartGuthaben = htmlText.indexOf("Ihr Guthaben:");
				int textLenght = 14;
				if (indexStartGuthaben == -1) {
					indexStartGuthaben = htmlText.indexOf("Votre crèdit:");
					textLenght = 14;
				}
				if (indexStartGuthaben == -1) {
					indexStartGuthaben = htmlText.indexOf("Il suo credito:");
					textLenght = 16;
				}
				if (indexStartGuthaben == -1) {
					indexStartGuthaben = htmlText.indexOf("O seu saldo:");
					textLenght = 13;
				}
				if (indexStartGuthaben == -1) {
					indexStartGuthaben = htmlText.indexOf("Your credit:");
					textLenght = 13;
				}
				this.GUTHABEN_CHF = htmlText.substring(indexStartGuthaben + textLenght, indexStartGuthaben + textLenght
						+ 11);
				this.log("indexOf Guthaben=" + indexStartGuthaben + " -- Guthaben=" + this.GUTHABEN_CHF);

				// Gratis SMS
				int indexStartSMSGuthaben = htmlText.indexOf("SMS gratis,");
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS gratuits,");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS gratuiti,");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS gratuitos,");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("free SMS,");
				}
				this.GUTHABEN_SMS_GRATIS = htmlText.substring(indexStartSMSGuthaben - 3, indexStartSMSGuthaben - 1);
				this.log("indexOf SMS gratis=" + indexStartSMSGuthaben + " -- " + this.GUTHABEN_SMS_GRATIS);

				// Gekaufte SMS
				indexStartSMSGuthaben = htmlText.indexOf("SMS gekauft");
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS achetès");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS acquistati");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("SMS comprado");
				}
				if (indexStartSMSGuthaben == -1) {
					indexStartSMSGuthaben = htmlText.indexOf("purchased SMS");
				}
				this.GUTHABEN_SMS_BEZAHLT = htmlText.substring(indexStartSMSGuthaben - 3, indexStartSMSGuthaben - 1);
				this.log("indexOf SMS gekauft=" + indexStartSMSGuthaben + " -- SMS gekauft="
						+ this.GUTHABEN_SMS_BEZAHLT);

				this.setBalance(context);
			}

			htmlText = null;

		} catch (Exception e) {
			Log.e(TAG, null, e);
			throw new WebSMSException(e.getMessage());
		}
	}

	private void setBalance(final Context context) {
		this.GUTHABEN_SMS_GRATIS = this.removeBracket(this.GUTHABEN_SMS_GRATIS);
		this.GUTHABEN_SMS_BEZAHLT = this.removeBracket(this.GUTHABEN_SMS_BEZAHLT);

		if (this.GUTHABEN_SMS_BEZAHLT != null && !this.GUTHABEN_SMS_BEZAHLT.equals(DUMMY)
				&& !this.GUTHABEN_SMS_BEZAHLT.equals("0")) {

			this.getSpec(context).setBalance(
					"Gratis=" + this.GUTHABEN_SMS_GRATIS + ", Bezahlt=" + this.GUTHABEN_SMS_BEZAHLT + ", "
							+ this.GUTHABEN_CHF);
		} else {
			this.getSpec(context).setBalance("Gratis=" + this.GUTHABEN_SMS_GRATIS + ", " + this.GUTHABEN_CHF);

		}
	}

	private String removeBracket(String string) {
		this.log("string vorher=" + string);
		if (string != null && string.contains(">")) {
			this.log("string in=" + string);
			string = string.replace(">", " ");
			string = string.trim();
		}
		this.log("string nachher=" + string);
		return string;
	}

	/**
	 * central logger
	 * 
	 * @param message
	 */
	private void log(final String message) {
		// Log.d(TAG, message);
	}

}
