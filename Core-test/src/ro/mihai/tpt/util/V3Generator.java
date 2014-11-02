package ro.mihai.tpt.util;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import ro.mihai.tpt.RATT;
import ro.mihai.tpt.model.City;
import ro.mihai.tpt.model.Estimate;
import ro.mihai.tpt.model.HourlyPlan;
import ro.mihai.tpt.model.Junction;
import ro.mihai.tpt.model.Line;
import ro.mihai.tpt.model.Path;
import ro.mihai.tpt.model.Station;
import ro.mihai.util.DetachableStream;
import ro.mihai.util.NullMonitor;

import au.com.bytecode.opencsv.CSVReader;

import junit.framework.TestCase;

public class V3Generator extends TestCase {
	
	public void testGenerator() throws Exception {
		// InputStream inp = new FileInputStream("Lines Stations and Junctions - Timisoara Public Transport.csv");
		// InputStream inp = new FileInputStream("linestations3.csv");
		InputStream inp = new FileInputStream("linestations.csv");
		CSVReader rd = new CSVReader(new InputStreamReader(inp));
		String[] row;
		City c = new City();
		
		Map<String, Station> stMap = new HashMap<String, Station>();
		Map<String, Junction> jMap = new HashMap<String, Junction>();
		
		rd.readNext(); // ignore header row
		while(null!=(row=rd.readNext())) {
			// row structure: 
			// 0:LineExtID, 1:RawLineName,
			// 2:StationExtID, 3:RawStationName, 4:StationNiceName (PathName), 5:StationShortName,
			// 6:JunctionName,7:StationLat, 8:StationLng,
			// 9:Invalid if true
			if( (row.length==1 && row[0].trim().isEmpty()) // ignore empty rows 
				|| (row.length>0 && row[0].equalsIgnoreCase("LineID")) // ignore intermediate headers	
				|| (row.length>9 && row[9].equalsIgnoreCase("true")) // ignore invalid rows 
				|| (row.length>1 && row[0].trim().isEmpty() && row[1].trim().isEmpty()) // no id, no line name
			) continue;
			Station st = stMap.get(row[2]);
			
			String[] nnp = nonEmpty(row[4]) ? parsePara(row[4].trim()) : new String[]{"",""};
			
			if(null==st) {
				st = c.newStation(row[2].trim(), row[3].trim());
				st.setNiceName(nnp[0]);
				st.setShortName(nonEmpty(row[5]) ? row[5].trim() : "");
				
				String jName = nonEmpty(row[6]) ? row[6].trim() : st.getShortName();
				Junction j = jMap.get(jName);
				if(null==j) {
					j = c.newJunction(jName);
					jMap.put(jName, j);
				}
				st.setJunction(j);
				j.addStation(st);
				
				if(nonEmpty(row[7]) && nonEmpty(row[8])) 
					st.setCoords(row[7].trim(), row[8].trim());
				else
					st.setCoords("","");
				
				stMap.put(row[2], st);
			}
			Line l = c.getLineByName(row[1].trim());
			if (null==l)
				l = c.newLine(row[1].trim());

			Path p = l.getPath(nnp[1]);
			if (null==p) {
				p = c.newPath(l, row[0].trim(), nnp[1]);
				p.setNiceName(nnp[1]);
				l.addPath(p);
			}
			p.concatenate(st, new HourlyPlan());
			st.addPath(p);
		}
		
		assertEquals(2,c.getLineByName("Tv9").getPaths().size());
		assertEquals(2,c.getLineByName("33").getPaths().size());
		
		
		/* * /
		for(Line l : c.getLines()) {
			l.pathMerge();
			assert(l.getPaths().size()==1 || l.getPaths().size()==2);
			
			if(l.getPaths().size()==2)
				for(Path p:l.getPaths())
					p.reOrder();
		}
		
		printCSV(c);
		/* */
		
		String fileName = "citylines.dat";
		FileOutputStream fos = new FileOutputStream(fileName); 
		c.saveToFile(fos);
		fos.close();
		
		
		City c1 = new City();
		DetachableStream fis = new DetachableStream.FromFile(fileName);
		c1.loadFromStream(fis, new NullMonitor(), RATT.CITY_DB_ENTRIES);
		for(Station s:c1.getStations())
			s.getName();
		assertTrue(c.getStations().size() > 0);
		assertEquals(c.getStations().size(), 	c1.getStations().size());
		
		assertTrue(c.getLines().size() > 0);
		assertEquals(c.getLines().size(), 		c1.getLines().size());

		assertTrue(c.getJunctions().size() > 0);
		assertEquals(c.getJunctions().size(), 	c1.getJunctions().size());
		
		for(Line l:c1.getLines())
			for(Path p:l.getPaths())
				assertEquals(l.getName(), p.getLineName());

		assertEquals(c.getLineByName("33").getPaths().size(), c1.getLineByName("33").getPaths().size());
		assertEquals(c.getLineByName("33").getSortedPathNames(), c1.getLineByName("33").getSortedPathNames());
		assertEquals(2,c.getLineByName("33").getPaths().size());

		assertEquals(c.getLineByName("32").getPaths().size(), c1.getLineByName("32").getPaths().size());
		assertEquals(c.getLineByName("32").getSortedPathNames(), c1.getLineByName("32").getSortedPathNames());
		assertEquals(2,c.getLineByName("32").getPaths().size());

		assertEquals(c.getLineByName("Tv9").getPaths().size(), c1.getLineByName("Tv9").getPaths().size());
		assertEquals(2,c.getLineByName("Tv9").getPaths().size());
		
		assertPaths(c,"generated");
		assertPaths(c1,"saved");
		// stream must stay open for certain stuff
		fis.close();
	}

	private void assertPaths(City c, String descr) {
		for(Line l : c.getLines()) {
			if(l.getPaths().size() != 2) {
				System.out.println("Err("+descr+"): "+l.getName()+" has "+l.getPaths().size()+" paths.");
				for(Path p: l.getPaths())
					System.out.println("\t"+p.getName());
			}
			assertTrue(l.getPaths().size() <= 2);
		}
	}
	
	
	
	private static boolean nonEmpty(String s) {
		return s!=null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s);
	}
	
	private static String[] parsePara(String niceNameWithPara) {
		int lpi = niceNameWithPara.lastIndexOf('(');
		int lcpi = niceNameWithPara.indexOf(')', lpi);
		if(lpi<0) return new String[]{niceNameWithPara,""};
		if (lcpi<=lpi) lcpi = niceNameWithPara.length(); 
		
		return new String[]{niceNameWithPara.substring(0,lpi), niceNameWithPara.substring(lpi+1, lcpi)};
	}
	
	@SuppressWarnings("unused")
	private static void printCSV(City city) throws IOException {
		PrintStream csv = new PrintStream(new FileOutputStream("linestations2.csv"));
		csv.println("LineID, LineName, StationID, RawStationName, FriendlyStationName, ShortStationName, JunctionName, Lat, Long, Invalid, Verified, Verification Date, Goodle Maps Link");
		
		for(Line l : city.getLinesSorted()) {
			for(Path p:l.getPaths()) {
				for(Estimate e : p.getEstimatesByPath()) {
					Station s = e.getStation();
					StringBuilder b = new StringBuilder();
					b.append(p.getId()); b.append(",");
					
					b.append("\""+checked(l.getName())+"\""); b.append(",");
					
					b.append(s.getId()); b.append(",");
					b.append("\""+checked(s.getName())+"\""); b.append(",");
					String dir = "";
					if(!p.getName().trim().isEmpty()) 
						dir = " ("+p.getName()+")";
					
					b.append("\""+checked(s.getNiceName())+dir+"\""); 	b.append(",");
					b.append("\""+checked(s.getShortName())+"\"");		b.append(",");
					b.append("\""+checked(s.getJunctionName())+"\"");	b.append(",");
					b.append("\""+checked(s.getLat())+"\"");			b.append(",");
					b.append("\""+checked(s.getLng())+"\"");			b.append(",");

					b.append("\""+"\"");			b.append(",");
					b.append("\""+"\"");			b.append(",");
					b.append("\"07.08.2011\"");		b.append(",");
					
					if(checked(s.getLat()).isEmpty() || checked(s.getLng()).isEmpty())
						b.append("\""+"\"");
					else
						b.append("\"http://maps.google.com/maps?q="+underlined(s.getName())+"@"+checked(s.getLat())+","+checked(s.getLng())+"\"");
					
					csv.println(b.toString());
				}
			}
		}
		
		csv.close();
	}
	
	private static String checked(String s) {
		if(null==s) return "";
		return s.trim();
	}
	
	private static String underlined(String s) {
		return s.replace(" ", "_").replace("-", "_").replace(".", "_");
	}
	
}
