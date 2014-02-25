/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.multionewire.internal;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.openhab.binding.multionewire.MultiOneWireBindingProvider;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.owfs.jowfsclient.Enums.OwBusReturn;
import org.owfs.jowfsclient.Enums.OwDeviceDisplayFormat;
import org.owfs.jowfsclient.Enums.OwPersistence;
import org.owfs.jowfsclient.Enums.OwTemperatureScale;
import org.owfs.jowfsclient.OwfsClientFactory;
import org.owfs.jowfsclient.OwfsException;
import org.owfs.jowfsclient.internal.OwfsClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RefreshService polls all configured OneWireSensors with a configurable
 * interval and post all values on the internal event bus. The interval is 1
 * minute by default and can be changed via openhab.cfg.
 * 
 * @author Thomas.Eichstaedt-Engelen
 * @since 0.6.0
 */
public class MultiOneWireBinding extends AbstractActiveBinding<MultiOneWireBindingProvider> implements ManagedService {

	private static final Logger logger = LoggerFactory.getLogger(MultiOneWireBinding.class);

	/**
	 * the refresh interval which is used to poll values from the OneWire server
	 * (optional, defaults to 60000ms)
	 */
	private long refreshInterval = 60000;

	/** the retry count in case no valid value was returned upon read (optional, defaults to 3) */
	private int retry = 3;

	/** defines which temperature scale owserver should return temperatures in (optional, defaults to CELSIUS) */
	private OwTemperatureScale tempScale = OwTemperatureScale.OWNET_TS_CELSIUS;

	private Map<String, ServerConfig> serverList = new HashMap<String, ServerConfig>();
	
	private static final Pattern EXTRACT_CONFIG_PATTERN = Pattern.compile("^(.*?)\\.(host)$");


	@Override
	protected String getName() {
		return "MultiOneWire Refresh Service";
	}

	@Override
	protected long getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * Create a new {@link OwClient} with the given <code>serverId</code>
	 * 
	 * @param serverId
	 */
	private OwfsClientImpl connect(int serverId) throws IllegalArgumentException {
		
		ServerConfig config = serverList.get(Integer.toString(serverId));
		
		if(config == null)
			{
				String message = "Server with id " + Integer.toString(serverId) + " not found.";
				logger.error(message);
				throw new IllegalArgumentException(message);
			}
		
		if (config.host != null && config.port > 0) {
			OwfsClientImpl owc = (OwfsClientImpl) OwfsClientFactory.newOwfsClient(config.host, config.port, false);

			/* Configure client */
			owc.setDeviceDisplayFormat(OwDeviceDisplayFormat.OWNET_DDF_F_DOT_I);
			owc.setBusReturn(OwBusReturn.OWNET_BUSRETURN_ON);
			owc.setPersistence(OwPersistence.OWNET_PERSISTENCE_ON);
			owc.setTemperatureScale(tempScale);
			owc.setTimeout(5000);

			try {
				boolean isConnected = owc.connect();
				if (isConnected) {
					logger.info("Established connection to OwServer on IP '{}' Port '{}'.",	config.host, config.port);
					return owc;
				} else {
					logger.warn("Establishing connection to OwServer [IP '{}' Port '{}'] timed out.", config.host, config.port);
				}
			} catch (IOException ioe) {
				logger.error("Couldn't connect to OwServer [IP '" + config.host + "' Port '" + config.port + "']: ", ioe.getLocalizedMessage());
			}
		} else {
			logger.warn("Couldn't connect to OwServer because of missing connection parameters [IP '{}' Port '{}'].", config.host, config.port);
		}
		
		return null;
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	public void execute() {
		for (MultiOneWireBindingProvider provider : providers) {
			for (String itemName : provider.getItemNames()) {

				String sensorId = provider.getSensorId(itemName);
				String unitId = provider.getUnitId(itemName);
				int serverId = provider.getServerId(itemName);

				if (sensorId == null || unitId == null || serverId == -1) {
					logger.warn("sensorId or unitId or serverId isn't configured properly "
							+ "for the given itemName [itemName={}, sensorId={}, unitId={}, serverId={}] => querying bus for values aborted!",
							new Object[] { itemName, sensorId, unitId,serverId });
					continue;
				}

				State value = UnDefType.UNDEF;

				try {
					OwfsClientImpl owc = connect(serverId);
					if (owc.exists("/" + sensorId)) {
						int attempt = 1;
						Item item = provider.getItem(itemName);
						while (value == UnDefType.UNDEF && attempt <= retry) {
							String valueString = owc.read(sensorId + "/" + unitId);
							logger.debug("{}: Read value '{}' from {}/{} at server-id {}, attempt={}",
									new Object[] { itemName, valueString, sensorId, unitId, serverId, attempt });
							if (valueString != null) {
								if (item instanceof ContactItem) {
									value = valueString.trim().equals("1") ? OpenClosedType.CLOSED : OpenClosedType.OPEN;
								} else if (item instanceof SwitchItem) {
									value = valueString.trim().equals("1") ? OnOffType.ON : OnOffType.OFF;
								} else if (item instanceof NumberItem) {
									value = new DecimalType(Double.valueOf(valueString));
								} else {
									throw new IllegalStateException(
										"The item with name " + itemName + " is not a valid type.");
								}
							}
							attempt++;
						}
					} else {
						logger.info("there is no sensor for path {}",
								sensorId);
					}
					owc.disconnect();
					logger.debug("Found sensor {} with value {}", sensorId, value);
				} catch (OwfsException oe) {
					logger.warn("couldn't read from path {}", sensorId);
					if (logger.isDebugEnabled()) {
						logger.debug("reading from path " + sensorId + " throws exception", oe);
					}
				} catch (IOException ioe) {
					logger.error(
							"couldn't establish network connection while reading '"	+ sensorId + "'", ioe);
				} finally {
					Item item = provider.getItem(itemName);
					if (item != null) {
						synchronized (item) {
							if (!item.getState().equals(value)) {
								eventPublisher.postUpdate(itemName, value);
							}
						}
					}
				}
			}
		};
	}

	@SuppressWarnings("rawtypes")
	public void updated(Dictionary config) throws ConfigurationException {

		if (config == null) return;
			
		@SuppressWarnings("unchecked")
		Enumeration<String> keys = config.keys();

		if ( serverList == null ) {
			serverList = new HashMap<String, ServerConfig>();
		}
		
		
		while (keys.hasMoreElements()) {
			String key = (String) keys.nextElement();

			// the config-key enumeration contains additional keys that we
			// don't want to process here ...
			if ("service.pid".equals(key)) {
				continue;
			}

			Matcher matcher = EXTRACT_CONFIG_PATTERN.matcher(key);

			if (!matcher.matches()) {
				continue;
			}

			matcher.reset();
			matcher.find();

			String serverId = matcher.group(1);

			ServerConfig serverConfig = serverList.get(serverId);

			if (serverConfig == null) {
				serverConfig = new ServerConfig();
				serverList.put(serverId, serverConfig);
			}
			
			String configKey = matcher.group(2);
			String value = (String) config.get(key);

			if ("host".equals(configKey)) {
				String[] hostConfig = value.split(":");
				serverConfig.host = hostConfig[0];
				if(hostConfig.length > 1) {
					serverConfig.port = Integer.parseInt(hostConfig[1]);
				} else {
					serverConfig.port = 4304;
				}
				logger.debug("Added new Server {}:{} to serverlist", new Object[] {serverConfig.host, serverConfig.port});
			} else {
				throw new ConfigurationException(configKey, "The given OWServer configKey '" + configKey + "' is unknown");
			}
		}

		
		String refreshIntervalString = (String) config.get("refresh");
		if (StringUtils.isNotBlank(refreshIntervalString)) {
			refreshInterval = Long.parseLong(refreshIntervalString);
		}

		String retryString = (String) config.get("retry");
		if (StringUtils.isNotBlank(retryString)) {
			retry = Integer.parseInt(retryString);
		}

		String tempScaleString = (String) config.get("tempscale");
		if (StringUtils.isNotBlank(tempScaleString)) {
			try {
				tempScale = OwTemperatureScale.valueOf("OWNET_TS_" + tempScaleString);
			} catch (IllegalArgumentException iae) {
				throw new ConfigurationException(
						"onewire:tempscale","Unknown temperature scale '"
								+ tempScaleString + "'. Valid values are CELSIUS, FAHRENHEIT, KELVIN or RANKIN.");
			}
		}

		setProperlyConfigured(true);
	}
	
	@Override
	protected void internalReceiveCommand(String itemName, Command command) {
		logger.debug("Working on Item {}", new Object[] {itemName});
		for (MultiOneWireBindingProvider provider : providers) {
			String sensorId = provider.getSensorId(itemName);
			String unitId = provider.getUnitId(itemName);
			int serverId = provider.getServerId(itemName);

			if (sensorId == null || unitId == null || serverId == -1) {
				continue;
			}

			String value = null;
			if (command instanceof OnOffType && command.equals(OnOffType.ON)) {
				value = "1";
			} else if (command instanceof OnOffType && command.equals(OnOffType.OFF)) {
				value = "0";
			} else {
				value = command.toString();
			}

			try {
				OwfsClientImpl owc = connect(serverId);
				if (owc.exists("/" + sensorId) && (value != null)) {
					logger.debug("{}: writing value '{}' to {}/{}",
							new Object[] { itemName, value, sensorId, unitId });
					owc.write(sensorId + "/" + unitId, value);
				} else {
					logger.info("there is no sensor for path {}",
							sensorId);
				}
				owc.disconnect();
			} catch (OwfsException oe) {
				logger.warn("couldn't write to path {}", sensorId);
				if (logger.isDebugEnabled()) {
					logger.debug("writing to path " + sensorId + " throws exception", oe);
				}
			} catch (IOException ioe) {
				logger.error(
						"couldn't establish network connection while writing to '"	+ sensorId + "'", ioe);
			}
		}
	}
	
	static class ServerConfig {
		public String host;
		public Long lastUpdate;
		public int port;

		ServerConfig() {
			lastUpdate = (long) 0;	
		}
		
		@Override
		public String toString() {
			return "OWServerCache [host="+host+" last=" + lastUpdate + ", port=" + port + "]";
		}
	}
}
