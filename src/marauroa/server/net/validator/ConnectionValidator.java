/* $Id: ConnectionValidator.java,v 1.3 2007/02/03 17:33:44 arianne_rpg Exp $ */
/***************************************************************************
 *                      (C) Copyright 2003 - Marauroa                      *
 ***************************************************************************
 ***************************************************************************
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 ***************************************************************************/
package marauroa.server.net.validator;

import java.net.InetAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import marauroa.common.Log4J;
import marauroa.server.game.db.nio.IDatabase;
import marauroa.server.game.db.nio.JDBCDatabase;

import org.apache.log4j.Logger;

/**
 * The PacketValidator validates the ariving packets, (currently it can only
 * check if the address is banned, may be it will check more later)
 * 
 * FIXME: It WILL appear race conditions if it is accessed from outside the Network thread.
 * 
 */
public class ConnectionValidator implements Iterable<InetAddressMask>{
	/** the logger instance. */
	private static final Logger logger = Log4J.getLogger(ConnectionValidator.class);

	/** Permanent bans are stored inside the database. */
	private List<InetAddressMask> permanentBans;
	
	/** Temporal bans are added using the API and are lost on each server reset. 
	 *  Consider using Database for a permanent ban  */
	private List<InetAddressMask> temporalBans;

	/* timestamp of last reload */
	private long lastLoadTS;

	/* How often do we reload ban information from database? Each 5 minutes*/
	private final static long reloadAfter=5 * 60 * 1000;

	/**
	 * Constructor that opens the socket on the marauroa_PORT and start the
	 * thread to recieve new messages from the network.
	 */
	public ConnectionValidator() {
		Log4J.startMethod(logger, "PacketValidator");
		
		permanentBans=new LinkedList<InetAddressMask>();
		temporalBans=new LinkedList<InetAddressMask>();

		/* read ban list from configuration */
		loadBannedIPNetworkListFromDB();
		
		Log4J.finishMethod(logger, "PacketValidator");
	}
	
	/**
	 * This adds a temporal ban.
	 * @param address the address to ban
	 * @param mask mask to apply to the address
	 */
	public void addBan(String address, String mask) {
		temporalBans.add(new InetAddressMask(address,mask));
	}
	
	/** 
	 * Removes one of the added temporal bans. 
	 * @param address the address to remove
	 * @param mask the mask used.
	 * @return true if it has been removed.
	 */
	public boolean removeBan(String address, String mask) {
		return temporalBans.remove(new InetAddressMask(address,mask));
	}

	/**
	 * Returns an iterator over the temporal bans.
	 * To access permanent bans, use database facility.
	 */
	public Iterator<InetAddressMask> iterator() {
		return temporalBans.iterator();
	}

	/**
	 * Is the source ip-address banned?
	 * 
	 * @param address
	 *            the InetAddress of the source
	 * @return true if the source ip is banned
	 */
	public synchronized boolean checkBanned(InetAddress address) {
		Log4J.startMethod(logger, "checkBanned");

		checkReload();

		for(InetAddressMask iam: temporalBans) {
			if (iam.matches(address)) {
				logger.debug("Address " + address + " is temporally banned by " + iam);
				return true;
			}
		}

		for(InetAddressMask iam: permanentBans) {
			if (iam.matches(address)) {
				logger.debug("Address " + address + " is permanently banned by " + iam);
				return true;
			}
		}


		Log4J.finishMethod(logger, "checkBanned");
		return false;
	}

	public synchronized boolean checkBanned(Socket socket) {
		InetAddress address=socket.getInetAddress();
		return checkBanned(address);
	}

	/** loads and initializes the ban list from a database */
	public synchronized void loadBannedIPNetworkListFromDB() {
		Log4J.startMethod(logger, "loadBannedIPNetworkListFromDB");
		try {
			IDatabase db = JDBCDatabase.getDatabase();

			/* read ban list from DB */
			Connection connection = db.getTransaction().getConnection();
			Statement stmt = connection.createStatement();
			ResultSet rs = stmt.executeQuery("select address,mask from banlist");
			
			permanentBans.clear();
			while (rs.next()) {
				String address = rs.getString("address");
				String mask = rs.getString("mask");
				InetAddressMask iam = new InetAddressMask(address, mask);
				permanentBans.add(iam);
			}

			// free database resources
			rs.close();
			stmt.close();

			logger.debug("loaded " + permanentBans.size()+ " entries from ban table");
		} catch (SQLException sqle) {
			logger.error("cannot read banned networks database table", sqle);
		} catch (Exception e) {
			logger.error("error while reading banned networks", e);
		}

		lastLoadTS = System.currentTimeMillis();
		Log4J.finishMethod(logger, "loadBannedIPNetworkListFromDB");
	}

	/**
	 * checks if reload is necessary and performs it
	 */
	public synchronized void checkReload() {
		Log4J.startMethod(logger, "checkReload");
		if (System.currentTimeMillis() - lastLoadTS >= reloadAfter) {
			loadBannedIPNetworkListFromDB();
		}
		Log4J.finishMethod(logger, "checkReload");
	}
}