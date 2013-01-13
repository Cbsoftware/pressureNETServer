package ca.cumulonimbus.barometer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Logger;

import ca.cumulonimbus.barometer.BarometerReading;

public class DatabaseHelper {

	// Database objects
	Connection db;
	PreparedStatement pstmt;
	boolean connected = false;

	private static final int MAX = 100;
	private static String logName = "ca.cumulonimbus.barometer.DatabaseHelper";
	private static Logger log = Logger.getLogger(logName);

	private static double TENDENCY_HOURS = 12;
	private static double TENDENCY_DELTA = 0.5;
	
	
	/**
	 * Add a barometer reading to the database. Before inserting a new row, check to see if
	 * this user has submitted an entry before. If so, update the row.
	 * @param reading
	 * @return
	 */
	public boolean addReadingToDatabase(BarometerReading reading) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			// Check for existing ID in database
			ArrayList<BarometerReading> readings = new ArrayList<BarometerReading>();
			pstmt = db.prepareStatement("SELECT * FROM Readings WHERE text='" + reading.getAndroidId() + "'");
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				readings.add(resultSetToBarometerReading(rs));
			}
			log.info("adding a reading. existing entries for id: " + readings.size());
			if(readings.size() > 0) {
				// Exists. Update.
				pstmt = db.prepareStatement("UPDATE Readings SET latitude=?, longitude=?, daterecorded=?, reading=?, tzoffset=?, privacy=? WHERE text=?");
				pstmt.setDouble(1, reading.getLatitude());
				pstmt.setDouble(2, reading.getLongitude());
				pstmt.setDouble(3, reading.getTime());
				pstmt.setDouble(4, reading.getReading());
				pstmt.setInt(5, reading.getTimeZoneOffset());
				pstmt.setString(6, reading.getAndroidId());
				pstmt.setString(7, reading.getSharingPrivacy());
				pstmt.execute();
				//log.info("updating " + reading.getAndroidId() + " to " + reading.getReading());
			} else {
				// Doesn't exist. Insert a new row.
				pstmt = db.prepareStatement("INSERT INTO Readings (latitude, longitude, daterecorded, reading, tzoffset, text, privacy) values (?, ?, ?, ?, ?, ?, ?)");
				pstmt.setDouble(1, reading.getLatitude());
				pstmt.setDouble(2, reading.getLongitude());
				pstmt.setDouble(3, reading.getTime());
				pstmt.setDouble(4, reading.getReading());
				pstmt.setInt(5, reading.getTimeZoneOffset());
				pstmt.setString(6, reading.getAndroidId());
				pstmt.setString(7, reading.getSharingPrivacy());
				pstmt.execute();
				//log.info("inserting new " + reading.getAndroidId());
			}
			
			// Either way, add it to the archive.
			pstmt = db.prepareStatement("INSERT INTO archive (latitude, longitude, daterecorded, reading, tzoffset, text, privacy) values (?, ?, ?, ?, ?, ?, ?)");
			pstmt.setDouble(1, reading.getLatitude());
			pstmt.setDouble(2, reading.getLongitude());
			pstmt.setDouble(3, reading.getTime());
			pstmt.setDouble(4, reading.getReading());
			pstmt.setInt(5, reading.getTimeZoneOffset());
			pstmt.setString(6, reading.getAndroidId());
			pstmt.setString(7, reading.getSharingPrivacy());
			pstmt.execute();
			//log.info("archiving " + reading.getAndroidId());
			
			return true;
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return false;
		}
	}
	
	public class UserCollection {
		private ArrayList<BarometerReading> allReadings = new ArrayList<BarometerReading>();
		private String id;
		
		public UserCollection(BarometerReading firstReading, String id) {
			allReadings.add(firstReading);
			this.id = id;
		}
		
		public UserCollection(String id) {
			this.id = id;
		}
		
		public ArrayList<BarometerReading> getAllReadings() {
			return allReadings;
		}

		public void setAllReadings(ArrayList<BarometerReading> allReadings) {
			this.allReadings = allReadings;
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public void addReading(BarometerReading newReading) {
			allReadings.add(newReading);
		}
	}
	
	// Loop through the archive, and split out useful data into User Collections
	public ArrayList<UserCollection> getUCFromArchive(ArrayList<BarometerReading> archive) {
		ArrayList<UserCollection> users = new ArrayList<UserCollection>();
		
		// Loop through the archive, and split out useful data.
		for(BarometerReading single : archive) {
			String id = single.getAndroidId();
			boolean exists = false;
			for(UserCollection user : users) {
				if(user.getId().equals(id)) {
					// User exists in collection. Add the entry there.
					user.addReading(single);
					exists = true;
				}
			}
			if(!exists) {
				// User not in the collection. Add.
				users.add(new UserCollection(single, id));
			}
		}
		
		return users;
	}
	
	// Return tendency in simple terms for a given user id
	public String getSimpleTendencyFromUserID(String userID) {

		try {
			String tendency = "Unknown";
			long sinceWhen = Calendar.getInstance().getTimeInMillis() - (long)(TENDENCY_HOURS * 60 * 60 * 1000);
			// this result comes sorted by time
			ArrayList<UserCollection> ucs = getReadingsByUserAndTime(userID, sinceWhen, "mbar");
			
			UserCollection singleUser = ucs.get(0); // when including a legit user id, only one result will returned
			// ignore anything older than 5 hours, look for delta 
			// greater than 3.5 mbar
			ArrayList<BarometerReading> originalList = singleUser.getAllReadings();


			double firstReading = originalList.get(0).getReading();
			double lastReading = originalList.get(originalList.size() - 1).getReading();
			//log.info(firstReading + " " + lastReading);
			//log.info("is " + Math.abs(firstReading - lastReading) + " greater than  " +  TENDENCY_DELTA);
			if(Math.abs(firstReading - lastReading) > TENDENCY_DELTA) { 
				if(firstReading<lastReading) {
					tendency = "Rising";
				} else if (firstReading>lastReading) {
					tendency = "Falling";
				} else {
					tendency = "Steady";
				}
			}
			//log.info(tendency);
			return tendency;
		} catch(Exception e) {
			log.info("tendency error: " + e.getMessage());
			return "Unknown";
		}
	}
	
	
	private int findNewReadings(ArrayList<BarometerReading> archive, int days) {
		int total = 0;
		for(BarometerReading single : archive) {
			double dateCutoff = Calendar.getInstance().getTimeInMillis() - daysToMs(days); // Now - days in millis
			if(single.getTime()>dateCutoff) {
				total++;
			}
		}
		
		return total;
	}

	public String generateStatisticsForUsers() {
		String allUsers =  "";
		String serverURL = ""; //BarometerServlet?statistics=by_user&user_id=";
		try {
			pstmt = db.prepareStatement("select * from archive");
			ResultSet rs = pstmt.executeQuery();
			ArrayList<BarometerReading> archive = new ArrayList<BarometerReading>();
			while(rs.next()) {
				archive.add(resultSetToBarometerReading(rs));
			}
			ArrayList<UserCollection> users = new ArrayList<UserCollection>();

			users = getUCFromArchive(archive);
			
			allUsers = "Total: " + users.size() + "<hr/>";
			for(UserCollection user : users) {
				allUsers += "<a href='" + serverURL + user.getId() + "'>" + user.getId() + "</a>: " + user.getAllReadings().size() + "<br/>";
			}
			
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
		}
		return allUsers;
	}
	
	private int findMultipleSubmissions(ArrayList<UserCollection> users) {
		int total = 0;
		for(UserCollection user : users) {
			if(user.getAllReadings().size()>=2) {
				total++;
			}
		}
		return total;
	}
	
	public boolean deleteUserData(String userID) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("delete from readings where text=?");
			pstmt.setString(1, userID);
			boolean deletedFromReadings = pstmt.execute();
			pstmt = db.prepareStatement("delete from archive where text=?");
			pstmt.setString(1, userID);
			boolean deletedFromArchive = pstmt.execute();
			if(deletedFromReadings && deletedFromArchive) {
				return true;
			}
		} catch(SQLException e) {
			log.info(e.getMessage());
		}
		
		
		return false;
	}
	
	// use Google Charts
	public String getChartFromSingleUser(String userId, long sinceWhen, String units) {
		String html = "";
		ArrayList<UserCollection> ucs = getReadingsByUserAndTime(userId, sinceWhen, "mbar");
		if(ucs.size()==1) {
			UserCollection uc = ucs.get(0);
			ChartData cd = new ChartData("Pressure over Time");
			for(BarometerReading br : uc.getAllReadings()) {
				cd.addRow(br.getReading(), (long)br.getTime());
			}
			html = cd.getChartWebPage();
		} else {
			return "Error. Invalid data returned from server. Size is " + ucs.size() + " for user " + userId + " since " + sinceWhen;
		}
		return html;
	}
	
	// Get all a user's info and return it in CSV
	public String getUserCSV(String userId) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("select * from archive where text=? order by daterecorded");
			
			pstmt.setString(1, userId);
			ResultSet rs = pstmt.executeQuery();
			String csv = "";
			while(rs.next()) {
				BarometerReading current = resultSetToBarometerReading(rs, "mbar");
				Date d = new Date((long)current.getTime());
				csv += d.toLocaleString() + "," + current.getLatitude() + "," + current.getLongitude() + "," + current.getReading() + "\n";
			} 
			return csv;
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return "no data";
		}
		
	}
	
	// Return a set of useful information from a single user
	// All users, all time = (null,0L). Return the data chronologically
	public ArrayList<UserCollection> getReadingsByUserAndTime(String userId, long sinceWhen, String units) {
		ArrayList<UserCollection> uc = new ArrayList<UserCollection>();
		if(!connected) {
			connectToDatabase();
		}
		try {
			if(userId != null) {
				// Single user
				
				pstmt = db.prepareStatement("select * from archive where text=? and daterecorded >? order by daterecorded");
			} else {
				// All users
				pstmt = db.prepareStatement("select * from archive where daterecorded > ? order by daterecorded");
			}
			pstmt.setString(1, userId);
			pstmt.setLong(2, sinceWhen);
			ResultSet rs = pstmt.executeQuery();
			ArrayList<BarometerReading> readings = new ArrayList<BarometerReading>();
			while(rs.next()) {
				readings.add(resultSetToBarometerReading(rs, units));
			}
			uc = getUCFromArchive(readings);
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
		
		return uc;
	}
	
	public String getUserPercentile(String userId) {
		String percentile = "";
		if(!connected) {
			connectToDatabase();
		}
		try {

			
		} catch(Exception e) {
			
		}
		
		return percentile;
	}
	
	// Return a set of useful information from a single user
	public String generateStatisticsByUserAndTime(String userId, long sinceWhen) {
		String stats = "";
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("select * from archive where text=? and daterecorded > ?");
			pstmt.setString(1, userId);
			pstmt.setLong(2, sinceWhen);
			ResultSet rs = pstmt.executeQuery();
			ArrayList<BarometerReading> readings = new ArrayList<BarometerReading>();
			while(rs.next()) {
				readings.add(resultSetToBarometerReading(rs));
			}
			if(sinceWhen==0) {
				//stats = "Your total readings: " + readings.size();
			} else {
				// TODO: HACK! Fix this to give the correct time. Right now we only
				// call this function with sinceWhen = an hour, but that will change.
				stats = "Readings in the last day: " + readings.size();
			}

			
			return stats;
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return "error";
		}
	}
	
	// Return a set of useful information from only recent data in the archive
	public String generateRecentStatisticsFromArchive(String days) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("select * from archive");
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				
			}
		
		} catch(SQLException sqle) {
			
		}
		
		return "Service not yet implemented.";
	}

	
	/**
	 * 
	 * THE NEW PRESSURENET (v3)
	 * 
	 */
	
	public ArrayList<TrendWindow> getTrends() {
		ArrayList<TrendWindow> trends = new ArrayList<TrendWindow>();
		/*
		try {
			pstmt = db.prepareStatement("SELECT * FROM Trends");
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				double[] region = {rs.getDouble("minlatitude"), rs.getDouble("maxlatitude"), rs.getDouble("minlongitude"),rs.getDouble("maxlongitude")};
				Window window = new Window(region);
				TrendWindow trend = new TrendWindow(rs.getString("trend"), window);
				trends.add(trend);
			}
			log.info("fetched trends");
			return trends;
		} catch (SQLException sqle) {
			log.info(sqle.getMessage());
			return trends;
		}
		*/
		return trends;
	}
	

	public void saveTrends(ArrayList<TrendWindow> trendWindows) {
		clearTrends();
		try {
			for(TrendWindow trend : trendWindows) {
				pstmt = db.prepareStatement("INSERT INTO Trends (minlatitude, maxlatitude, minlongitude, maxlongitude, trend, archiveid) values (?, ?, ?, ?, ?, ?)");
				pstmt.setDouble(1, trend.window.minLatitude);
				pstmt.setDouble(2, trend.window.maxLatitude);
				pstmt.setDouble(3, trend.window.minLongitude);
				pstmt.setDouble(4, trend.window.maxLongitude);
				pstmt.setString(5, trend.trend);
				pstmt.setInt(6, -1);
				
				pstmt.execute();
				
			} 
			log.info("updated " + trendWindows.size() + " trends");
		} catch (SQLException sqle) {
			log.info(sqle.getMessage());
		}
	}

	
	
	// Return a set of useful information from all the data in the archive
	public ArrayList<BarometerReading> getRecentArchive() {
		ArrayList<BarometerReading> archive = new ArrayList<BarometerReading>();
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("select * from archive order by daterecorded limit 1000");
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				archive.add(resultSetToBarometerReading(rs));
			}
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
		}
		
		return archive;
	}
	
	// Return a set of useful information from all the data in the archive
	public ArrayList<BarometerReading> getFullArchive() {
		ArrayList<BarometerReading> archive = new ArrayList<BarometerReading>();
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("select * from archive");
			ResultSet rs = pstmt.executeQuery();
			while(rs.next()) {
				archive.add(resultSetToBarometerReading(rs));
			}
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
		}
		
		return archive;
	}
	
	

	// table is usually "readings" for only-single-datapoints, "archive" for historical user values
	public int getReadingCountWithinRegion(double[] region, long sinceWhen, String table ) {
		if(!connected) {
			connectToDatabase();
		}

		double lat1 = region[0];
		double lat2 = region[1];
		double lon1 = region[2];
		double lon2 = region[3];
		
		String sql = "SELECT count(*) FROM " + table + " WHERE latitude>? AND latitude<? AND longitude>? AND longitude<? and daterecorded>?";
		try {
			pstmt = db.prepareStatement(sql);
			pstmt.setDouble(1, lat1);
			pstmt.setDouble(2, lat2);
			pstmt.setDouble(3, lon1);
			pstmt.setDouble(4, lon2);
			pstmt.setLong(5, sinceWhen);
			
			ResultSet rs = pstmt.executeQuery();
			rs.next();
			int count = rs.getInt(1);
			
			return count;
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return -1;
		}
	}
	
	
	
	// table is usually "readings" for only-single-datapoints, "archive" for historical user values
	public ArrayList<BarometerReading> getReadingsWithinRegion(double[] region, long sinceWhen, String table ) {
		if(!connected) {
			connectToDatabase();
		}
		ArrayList<BarometerReading> readingsList = new ArrayList<BarometerReading>();

		double lat1 = region[0];
		double lat2 = region[1];
		double lon1 = region[2];
		double lon2 = region[3];
		
		//log.info("lat1: " + lat1 + ", lat2: " + lat2 + ", lon1: " + lon1 + ", lon2: " + lon2);
		//log.info(sinceWhen + " - " + Calendar.getInstance().getTimeInMillis());
		String sql = "SELECT * FROM " + table + " WHERE latitude>? AND latitude<? AND longitude>? AND longitude<? and daterecorded>?";
		try {
			pstmt = db.prepareStatement(sql);
			pstmt.setDouble(1, lat1);
			pstmt.setDouble(2, lat2);
			pstmt.setDouble(3, lon1);
			pstmt.setDouble(4, lon2);
			pstmt.setLong(5, sinceWhen);
			
			ResultSet rs = pstmt.executeQuery();
			
			while(rs.next()) {
				readingsList.add(resultSetToBarometerReading(rs));
			}
			return fudgeGPSData(readingsList);
			//return readingsList;
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	// Reading is stored in millibars. Convert to user-preferred unit.
	public double convertFromMbarsToCustomUnits(double reading, String units) {
		Unit u = new Unit(units);
		u.setValue(reading);
		return u.convertToPreferredUnit();
	}
	
	public BarometerReading resultSetToBarometerReading(ResultSet rs, String units) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			BarometerReading br = new BarometerReading();
			br.setLatitude(rs.getDouble("latitude"));
			br.setLongitude(rs.getDouble("longitude"));
			br.setReading(convertFromMbarsToCustomUnits(rs.getDouble("reading"), units));
			br.setTime(rs.getDouble("daterecorded"));
			br.setTimeZoneOffset(rs.getInt("tzoffset"));
			br.setAndroidId(rs.getString("text"));
			br.setSharingPrivacy(rs.getString("privacy"));
			return br;
		} catch (SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	public BarometerReading resultSetToBarometerReading(ResultSet rs) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			BarometerReading br = new BarometerReading();
			br.setLatitude(rs.getDouble("latitude"));
			br.setLongitude(rs.getDouble("longitude"));
			br.setReading(rs.getDouble("reading"));
			br.setTime(rs.getDouble("daterecorded"));
			br.setTimeZoneOffset(rs.getInt("tzoffset"));
			br.setAndroidId(rs.getString("text"));
			br.setSharingPrivacy(rs.getString("privacy"));
			
			// tendency
			/*
			long tendencyTimeAgo = (1000 * 60 * 60 * 5); // five hours
			long tendencySinceWhen = Calendar.getInstance().getTimeInMillis() - tendencyTimeAgo; // one week ago 
			ArrayList<UserCollection> userRecents = getReadingsByUserAndTime(rs.getString("text"),tendencyTimeAgo,"mbar");
			ArrayList<BarometerReading> brs = new ArrayList<BarometerReading>();
			for(UserCollection uc : userRecents) { // there will only be one since we passes a user id
				brs = uc.getAllReadings();
			}
			String tendency = ScienceHandler.findTendency(brs);
			BarometerReading.Tendency tendencyData = br.createTendency(tendency);
		
			
			br.setTendency(tendencyData);
			*/	
			return br;
		} catch (SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	private double daysToMs(int days) {
		return 1000 * 60 * 60 * 24 * days;
	}
	
	private ArrayList<BarometerReading> fudgeGPSData(ArrayList<BarometerReading> readings) {
		ArrayList<BarometerReading> fudgedReadings = new ArrayList<BarometerReading>();
		for(BarometerReading br : readings) {
			double longitude = br.getLongitude();
			double latitude = br.getLatitude();
			double range = .005;
			latitude = (latitude - range) + (int)(Math.random() * ((2 * range) + 1));
			longitude = (longitude - range) + (int)(Math.random() * ((2 * range) + 1));
			br.setLatitude(latitude);
			br.setLongitude(longitude);
			fudgedReadings.add(br);
		}
		
		return fudgedReadings;
	}
	
	public ArrayList<BarometerReading> getRecentReadings(int days) {
		if(!connected) {
			connectToDatabase();
		}
		if(connected) {
			ArrayList<BarometerReading> readings = new ArrayList<BarometerReading>();
			try {
				double dateCutoff = Calendar.getInstance().getTimeInMillis() - daysToMs(days); // Now - days in millis
				log.info("date cutoff: " + dateCutoff + " dayscuttingoffinms: " + daysToMs(days));
				pstmt = db.prepareStatement("SELECT * FROM Readings WHERE daterecorded > " + dateCutoff);
				ResultSet rs = pstmt.executeQuery();
				int i = 0;
				while(rs.next()) {
					i++;
					readings.add(resultSetToBarometerReading(rs));
					if(i>MAX) {
						break;
					}
				}
				return fudgeGPSData(readings);
			} catch(SQLException e) {
				log.info(e.getMessage());
				return null;
			}
		}
		return null;
	}
	
	public ArrayList<BarometerReading> getAllReadings() {
		if(!connected) {
			connectToDatabase();
		}
		if(connected) {
			ArrayList<BarometerReading> readings = new ArrayList<BarometerReading>();
			try {
				pstmt = db.prepareStatement("SELECT * FROM Readings");
				ResultSet rs = pstmt.executeQuery();
				int i = 0;
				while(rs.next()) {
					i++;
					readings.add(resultSetToBarometerReading(rs));
					if(i>MAX) {
						break;
					}
				}
				return fudgeGPSData(readings);
			} catch(SQLException sqle) {
				log.info(sqle.getMessage());
				return null;
			}
		}
		return null;
	}

	public ArrayList<BarometerReading> getReadingsAndTendenciesWithinRegion(ArrayList<Double> region, long sinceWhen ) {
		if(!connected) {
			connectToDatabase();
		}
		ArrayList<BarometerReading> readingsList = new ArrayList<BarometerReading>();

		double lat1 = region.get(0);
		double lat2 = region.get(1);
		double lon1 = region.get(2);
		double lon2 = region.get(3);

		long tendencyTimeAgo = (1000 * 60 * 60 * 5); // five hours
		long tendencySinceWhen = Calendar.getInstance().getTimeInMillis() - tendencyTimeAgo; // one week ago 
		
		log.info("lat1: " + lat1 + ", lat2: " + lat2 + ", lon1: " + lon1 + ", lon2: " + lon2);
		log.info(sinceWhen + " - " + Calendar.getInstance().getTimeInMillis());
		String sql = "SELECT * FROM Readings WHERE latitude>? AND latitude<? AND longitude>? AND longitude<? and daterecorded>?";
		try {
			pstmt = db.prepareStatement(sql);
			pstmt.setDouble(1, lat1);
			pstmt.setDouble(2, lat2);
			pstmt.setDouble(3, lon1);
			pstmt.setDouble(4, lon2);
			pstmt.setLong(5, tendencySinceWhen);
			
			ResultSet rs = pstmt.executeQuery();
			int i = 0;
			while(rs.next()) {
				i++;
				
				//ArrayList<UserCollection> userRecents = getReadingsByUserAndTime()
				//String tendency = ScienceHandler.findTendency(single_recents);
				readingsList.add(resultSetToBarometerReading(rs));
				
				if(i>MAX) {
					break;
				}
			}
			
			return fudgeGPSData(readingsList);
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	public ArrayList<BarometerReading> getReadingsWithinRegion(ArrayList<Double> region, long sinceWhen ) {
		if(!connected) {
			connectToDatabase();
		}
		ArrayList<BarometerReading> readingsList = new ArrayList<BarometerReading>();

		double lat1 = region.get(0);
		double lat2 = region.get(1);
		double lon1 = region.get(2);
		double lon2 = region.get(3);
		
		//log.info("lat1: " + lat1 + ", lat2: " + lat2 + ", lon1: " + lon1 + ", lon2: " + lon2);
		//log.info(sinceWhen + " - " + Calendar.getInstance().getTimeInMillis());
		String sql = "SELECT * FROM Readings WHERE latitude>? AND latitude<? AND longitude>? AND longitude<? and daterecorded>?";
		try {
			pstmt = db.prepareStatement(sql);
			pstmt.setDouble(1, lat1);
			pstmt.setDouble(2, lat2);
			pstmt.setDouble(3, lon1);
			pstmt.setDouble(4, lon2);
			pstmt.setLong(5, sinceWhen);
			
			ResultSet rs = pstmt.executeQuery();
			int i = 0;
			while(rs.next()) {
				i++;
				readingsList.add(resultSetToBarometerReading(rs));
				if(i>MAX) {
					break;
				}
			}
			return fudgeGPSData(readingsList);
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	public BarometerReading getReadingById(int id) {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("SELECT * FROM Readings WHERE id=" + id);
			ResultSet rs = pstmt.executeQuery();
			if(rs.next()) {
				BarometerReading br = resultSetToBarometerReading(rs);
				return br;
			} else {
				return null;
			}
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
			return null;
		}
	}
	
	public void clearTrends() {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("DELETE FROM Trends;");
			pstmt.execute();
			
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
		}
	}
	
	public void create() {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("DROP TABLE Trends;");
			pstmt = db.prepareStatement("DROP TABLE Archive;");
			pstmt = db.prepareStatement("DROP TABLE Readings;");
			
			
			pstmt.execute();
			
			pstmt = db.prepareStatement("CREATE TABLE Trends (id serial,	minlatitude numeric, maxlatitude numeric, minlongitude numeric, maxlongitude numeric, trend varchar(10), archiveid integer)");
			
			pstmt = db.prepareStatement("CREATE TABLE Archive (id serial,	latitude numeric, longitude numeric, daterecorded numeric, reading numeric, tzoffset int, text varchar(200), privacy varchar(100))");
			
			pstmt = db.prepareStatement("CREATE TABLE Readings (id serial,	latitude numeric, longitude numeric, daterecorded numeric, reading numeric, tzoffset int, text varchar(200))");
			pstmt.execute();
		} catch(SQLException e) {
			log.info(e.getMessage());
		}
		
	}
	
	public void cleanDatabase() {
		if(!connected) {
			connectToDatabase();
		}
		try {
			pstmt = db.prepareStatement("DELETE FROM Trends");
			pstmt = db.prepareStatement("DELETE FROM Readings");
			pstmt = db.prepareStatement("DELETE FROM Archive");
			pstmt.execute();
		} catch(SQLException sqle) {
			log.info(sqle.getMessage());
		}
	}
	
	public void connectToDatabase() {
		try {
			Class.forName("org.postgresql.Driver");
			String url = "jdbc:postgresql://localhost"; // LIVE: breadings // DEV: dev_archive
			Properties props = new Properties();
			props.setProperty("user","USER");
			props.setProperty("password","PASS");
			db = DriverManager.getConnection(url, props);
			connected = true;
			return;
		}
		catch(SQLException e) {
			log.info("sqle " + e.getMessage());
			connected = false;
		}
		catch(ClassNotFoundException cnfe) {
			log.info("class nfe " + cnfe.getMessage());
			connected = false;
		}
	}
	
	public DatabaseHelper () {
		connectToDatabase();
		if(!connected) {
			System.out.println("unable to connect to the database");
		}
	}
}

