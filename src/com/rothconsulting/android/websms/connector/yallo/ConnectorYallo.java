/*
 * Copyright (C) 2010 Koni Roth
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

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;

import org.apache.http.HttpResponse;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorCommand;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;
import de.ub0r.android.websms.connector.common.Log;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;

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

	private static final String YALLO_ENCODING = "ISO-8859-1";

	private String GUTHABEN_CHF = "???";
	private String GUTHABEN_SMS = "???";

	/** Check whether this connector is bootstrapping. */
	private static boolean inBootstrap = false;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec initSpec(final Context context) {
		final String name = context.getString(R.string.connector_yallo_name);
		ConnectorSpec c = new ConnectorSpec(name);
		c.setAuthor(context.getString(R.string.connector_yallo_author));
		c.setBalance(null);
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
		Log.d(TAG, "Start updateSpec");
		final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		if (p.getBoolean(Preferences.PREFS_ENABLED, false)) {
			if (p.getString(Preferences.PREFS_USER, "").length() > 0
					&& p.getString(Preferences.PREFS_PASSWORD, "") // .
							.length() > 0) {
				connectorSpec.setReady();
			} else {
				connectorSpec.setStatus(ConnectorSpec.STATUS_ENABLED);
			}
		} else {
			connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
		}
		Log.d(TAG, "End updateSpec");
		return connectorSpec;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doBootstrap(final Context context, final Intent intent)
			throws WebSMSException {
		Log.d(TAG, "Start doBootstrap");
		if (inBootstrap) {
			Log.d(TAG, "already in bootstrap: skip bootstrap");
			return;
		}

		StringBuilder url = new StringBuilder(URL_LOGIN);
		inBootstrap = true;
		final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		url.append("?j_username=" + p.getString(Preferences.PREFS_USER, ""));
		url.append("&j_password=" + p.getString(Preferences.PREFS_PASSWORD, ""));
		Log.d(TAG, "url=" + url);
		this.sendData(url.toString(), context);
		Log.d(TAG, "End doBootstrap");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doUpdate(final Context context, final Intent intent)
			throws WebSMSException {
		Log.d(TAG, "Start doUpdate");
		this.doBootstrap(context, intent);

		this.sendData(URL_SENDSMS, context);
		this.getSpec(context).setBalance("SMS=" + this.GUTHABEN_SMS + " / " + this.GUTHABEN_CHF);

		Log.d(TAG, "End doUpdate");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doSend(final Context context, final Intent intent) throws WebSMSException {
		Log.d(TAG, "Start doSend");
		this.doBootstrap(context, intent);

		ConnectorCommand command = new ConnectorCommand(intent);
		String text = "";
		try {
			text = URLEncoder.encode(command.getText(), YALLO_ENCODING);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			throw new WebSMSException(context, R.string.error_service);
		}
		Log.d(TAG, "message=" + text);

		String[] to = command.getRecipients();
		if (to == null || to.length > 1) {
			if (to != null) {
				Log.d(TAG, "Error: to.length=" + to.length);
			}
			throw new WebSMSException(context, R.string.error_only_one_recipient_allowed);
		}
		Log.d(TAG, "OK: to.length=" + to.length);
		String receiver = to[0];
		int start = receiver.indexOf("<");
		int end = receiver.indexOf(">");
		String destination = receiver.substring(start + 1, end);

		StringBuilder url = new StringBuilder(URL_SENDSMS);

		url.append("?destination=" + destination);
		url.append("&charsLeft=" + "" + (130 - text.length()));
		url.append("&message=" + text);
		url.append("&send=%A0senden");
		Log.d(TAG, "url=" + url);
		// push data
		this.sendData(url.toString(), context);
		Log.d(TAG, "End doSend");
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
	private void sendData(final String fullTargetURL, final Context context) throws WebSMSException {
		Log.d(TAG, "Start sendData");
		try { // get Connection

			Log.d(TAG, "--HTTP GET--");
			Log.d(TAG, fullTargetURL.toString());
			Log.d(TAG, "--HTTP GET--");
			// send data
			HttpResponse response = Utils.getHttpClient(fullTargetURL, null, null, null, null,
					null, true);
			int resp = response.getStatusLine().getStatusCode();
			if (resp != HttpURLConnection.HTTP_OK) {
				throw new WebSMSException(context, R.string.error_http, "" + resp);
			}
			String htmlText = Utils.stream2str(response.getEntity().getContent()).trim();
			if (htmlText == null || htmlText.length() == 0) {
				throw new WebSMSException(context, R.string.error_service);
			}
			Log.d(TAG, "--HTTP RESPONSE--");
			Log.d(TAG, htmlText);
			Log.d(TAG, "--HTTP RESPONSE--");

			int indexStartGuthaben = htmlText.indexOf("Ihr Guthaben:");
			if (indexStartGuthaben == -1) {
				indexStartGuthaben = htmlText.indexOf("Votre crédit:");
			}
			if (indexStartGuthaben == -1) {
				indexStartGuthaben = htmlText.indexOf("Il suo credito:");
			}
			if (indexStartGuthaben == -1) {
				indexStartGuthaben = htmlText.indexOf("O seu saldo:");
			}
			if (indexStartGuthaben == -1) {
				indexStartGuthaben = htmlText.indexOf("Your credit:");
			}
			this.GUTHABEN_CHF = htmlText
					.substring(indexStartGuthaben + 14, indexStartGuthaben + 25);
			Log.d(TAG, "indexOf Guthaben=" + indexStartGuthaben + " -- " + this.GUTHABEN_CHF);

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
			this.GUTHABEN_SMS = htmlText.substring(indexStartSMSGuthaben - 3,
					indexStartSMSGuthaben - 1);
			Log.d(TAG, "indexOf SMS gratis=" + indexStartSMSGuthaben + " -- " + this.GUTHABEN_SMS);

			htmlText = null;

		} catch (Exception e) {
			Log.e(TAG, null, e);
			throw new WebSMSException(e.getMessage());
		}
	}
}
