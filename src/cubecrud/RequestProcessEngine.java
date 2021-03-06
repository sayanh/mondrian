package cubecrud;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import mondrian.olap.CacheControl;
import mondrian.olap.Cube;
import mondrian.rolap.RolapSchema;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 *
 * This class provides a REST API for cube management in the Mondrian  
 * and also has functionality to invalidate the cache in Mondrian after
 * data change in database.
 * 
 */

/**
 *
 * @author SHazra
 * 
 */

@Path("/")
public class RequestProcessEngine {
	private static final org.apache.log4j.Logger LOGGER = Logger
			.getLogger(RequestProcessEngine.class);
	final static String DATASOURCE_PATH = System.getProperty("user.dir")
			+ "/webapps/mondrian/WEB-INF/datasources.xml";
	String result = "";
	static boolean isPresentCubeHand = false;
	static boolean isPresentCatalogHand = false;
	static String catalogFileForAdd = "";

	@Path("/catalog/{c}")
	@GET
	@Produces("application/xml")
	public String getCatalogs(@PathParam("c") String catalogName) {
		LOGGER.info("Querying for catalog: " + catalogName);
		String output = "";
		try {
			if (checkCatalogInDataSource(catalogName)) {

				output = getCube(DATASOURCE_PATH, "", catalogName);
				LOGGER.info(output);
			} else {
				output = "<output>No Catalogs found.</output>";
				LOGGER.info(output);
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return output;
	}

	@Path("/catalog/{c}")
	@DELETE
	@Produces("application/xml")
	public String deleteCatalog(@PathParam("c") String catalogName) {
		LOGGER.info("Deleting catalog: " + catalogName);
		String result = "";
		boolean isDelCatalogSuccess = false;
		try {
			String userDir = System.getProperty("user.dir");
			String userDirArr[] = userDir.split("/");
			String dirCatalogFiles = "/";
			for (String folderName : userDirArr) {
				if (folderName != null && !"".equalsIgnoreCase(folderName)) {
					dirCatalogFiles = dirCatalogFiles + folderName + "/";
					if (folderName.contains("apache")) {
						break;
					}
				}
			}

			dirCatalogFiles = dirCatalogFiles
					+ "webapps/mondrian/WEB-INF/queries/";
			if (deleteCatalogFromDatasource(catalogName)) {
				deleteFile(dirCatalogFiles + catalogName + ".xml");
				isDelCatalogSuccess = true;
			}
			result = isDelCatalogSuccess ? "Catalog successfully deleted"
					: "Deletion failed";
		} catch (Exception e) {
			result = e.getMessage();
		}

		result = "<output>" + result + "</output>";
		LOGGER.info(result);
		return result;
	}

	@Path("/cube/{c}/{d}")
	@GET
	@Produces("application/xml")
	public String getCubes(@PathParam("c") String catalogName,
			@PathParam("d") String cubeName) {
		String result = getCube(DATASOURCE_PATH, cubeName, catalogName);
		LOGGER.info(result);
		return result;
	}

	@Path("/cube/{c}")
	@GET
	@Produces("application/xml")
	public String getCubes(@PathParam("c") String cubeName) {
		String result = getCube(DATASOURCE_PATH, cubeName, "");
		LOGGER.info(result);
		return result;
	}

	@Path("/cubes")
	@GET
	@Produces("application/xml")
	public String getAllCubes() {
		System.out.println("Datasource = " + DATASOURCE_PATH);
		System.out.println("Current user dir is ="
				+ System.getProperty("user.dir"));
		String result = getCube(DATASOURCE_PATH, "", "");
		LOGGER.info(result);
		LOGGER.info("user dir=" + System.getProperty("user.dir"));
		return result;
	}

	@Path("/deletecube/{c}/{d}")
	@DELETE
	@Produces("application/xml")
	public String deleteTask(@PathParam("c") String catalogName,
			@PathParam("d") String cubeName) {
		String result = "";
		try {
			result = deleteCube(cubeName, catalogName) ? "Cube successfully deleted"
					: "Deletion failed";
		} catch (Exception e) {
			result = "Deletion failed" + e.getMessage();
		}

		result = "<output>" + result + "</output>";
		LOGGER.info(result);
		return result;
	}

	@Path("/putcube/{c}/{d}")
	@PUT
	@Produces("application/xml")
	@Consumes("text/plain")
	public String putTask(@PathParam("c") String catalogName,
			@PathParam("d") String cubeName, String inputXml) {
		LOGGER.info("Creating cube: catalog_name: " + catalogName
				+ " cube_name: " + cubeName);
		LOGGER.info("Input xml: " + inputXml);
		String result = "<output>" + addCube(inputXml, catalogName, cubeName)
				+ "</output>";
		LOGGER.info("response xml = " + result);
		return result;
	}

	/*
	 * Creates a catalog
	 * 
	 * @param catalogName, inputXml inputXml contains connnect_string details.
	 */
	@Path("/catalog/{c}")
	@PUT
	@Produces("application/xml")
	@Consumes("text/plain")
	public String createCatalog(@PathParam("c") String catalogName,
			String inputXml) {
		LOGGER.info("Creating catalog: " + catalogName);
		LOGGER.info("Input XML: " + inputXml);
		Document doc = null;
		NodeList nodeList = null;

		// Validation of input xml
		if (!inputXml.contains("DataSource")
				|| !inputXml.contains("JdbcDrivers=")
				|| !inputXml.contains("Jdbc=")) {
			LOGGER.info("Invalid input request!!");
			return "<output>Catalog creation failed| reason: Invalid input request!!</output>";
		}

		String result = "";
		try {
			ArrayList<Object> arrList = getNodeList(inputXml,
					"CatalogDefinition", false);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}
			Node e = nodeList.item(0);
			LOGGER.info("length of the node list =" + nodeList.getLength());
			System.out.println("value of the node =" + e.getNodeName());
			LOGGER.info("value of datasource = " + e.getTextContent());
			String dataSourceInfo = e.getTextContent().replace("&", "&#38;");

			LOGGER.info("value of datasource = " + e.getNodeValue());
			// Check the presence of the catalog if present then do not add else
			// add
			for (RolapSchema schema : RolapSchema.getRolapSchemas()) {
				if (schema.getName().equals(catalogName)) {
					LOGGER.info("Catalog " + catalogName + " was found");
					return "<output>Catalog " + catalogName
							+ " already exists</output>";
				}
			}

			boolean isCatalogPresentInDatasource = checkCatalogInDataSource(catalogName);

			LOGGER.info("Is catalog present in the datasource = "
					+ isCatalogPresentInDatasource);

			if (isCatalogPresentInDatasource) {
				return "<output>Catalog " + catalogName
						+ " already exists</output>";
			}

			String catalogFileName = addCatalogInDataSource(catalogName,
					dataSourceInfo);
			createCatalogDefinition(catalogName, catalogFileName, "");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return "<output>Catalog creation failed| reason: " + e.getMessage()
					+ "</output>";
		}
		return "<output>Catalog creation was successful</output>";
	}

	@Path("/invalidatecache/catalog/{c}")
	@PUT
	@Produces("application/xml")
	@Consumes("text/plain")
	public String invalidateCacheCatalog(@PathParam("c") String catalogName)
			throws SQLException {
		boolean isCatalogFound = false;
		try {
			System.out.println("Inside invalidate cache with catalog name="
					+ catalogName);
			for (RolapSchema schema : RolapSchema.getRolapSchemas()) {
				if (schema.getName().equals(catalogName)) {
					isCatalogFound = true;
					LOGGER.debug("schema is same as catalog and is flushing the schema");
					schema.getInternalConnection().getCacheControl(null)
							.flushSchemaCache();
				}
			}
			if (!isCatalogFound) {
				return "<output>Catalog " + catalogName
						+ " was not found </output>";
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOGGER.error("Error in RequestProcessEngine | invalidateCache "
					+ e.getMessage());
			e.printStackTrace();
			return "<output>Error in clearing cache for catalog " + catalogName
					+ " | " + e.getMessage() + "</output>";
		}
		return "<output>Cache clearance for Catalog " + catalogName
				+ " is successful</output>";
	}

	@Path("/invalidatecache/cube/{c}")
	@PUT
	@Produces("application/xml")
	@Consumes("text/plain")
	public String invalidateCacheCube(@PathParam("c") String cubeName)
			throws SQLException {
		// Warning: Leads to inconsistency in the view if the atomicity of the
		// flush and DB update
		// are not taken in to account
		boolean isCubePresent = false;
		try {
			for (RolapSchema schema : RolapSchema.getRolapSchemas()) {
				Cube[] cubeArr = schema.getCubes();
				CacheControl cacheControlObj = schema.getInternalConnection()
						.getCacheControl(null);
				for (Cube cube : cubeArr) {
					if (cube.getName().equals(cubeName)) {
						isCubePresent = true;
						LOGGER.debug("cube entered is same as cube and is flushing the cube");
						cacheControlObj.flush(cacheControlObj
								.createMeasuresRegion(cube));
					}
				}
			}
			if (!isCubePresent) {
				return "<output>Cube " + cubeName + " was not found </output>";
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOGGER.error("Error in RequestProcessEngine | invalidateCache "
					+ e.getMessage());
			e.printStackTrace();
			return "<output>Error in clearing cache for cube " + cubeName
					+ " | " + e.getMessage() + "</output>";
		}
		return "<output>Cache clearance for Cube " + cubeName
				+ " is successful</output>";
	}

	private static String parseCubeXml(String fileName, String cubeName) {
		StreamResult result = null;
		StringWriter sw = new StringWriter();
		Document doc = null;
		NodeList nodeList = null;
		int count = 0;
		try {
			ArrayList<Object> arrList = getNodeList(fileName, "Schema/Cube",
					true);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}
			TransformerFactory tFactory = TransformerFactory.newInstance();
			Transformer transformer = tFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
					"yes");
			result = new StreamResult(sw);

			DOMSource source = null;
			for (int x = 0; x < nodeList.getLength(); x++) {
				Node e = nodeList.item(x);
				NamedNodeMap attrs = e.getAttributes();
				if ("".equalsIgnoreCase(cubeName)) {
					if (e.getNodeType() == Node.ELEMENT_NODE) {
						source = new DOMSource(e);
						transformer.transform(source, result);
						count = count + 1;
					}
				} else {
					if (attrs.getNamedItem("name") != null) {
						String nameToMatch = attrs.getNamedItem("name")
								.getNodeValue();
						if (nameToMatch.equals(cubeName)) {
							source = new DOMSource(e);
							transformer.transform(source, result);
							break;
						}
					}

				}
			}
			LOGGER.info("Total cubes in the file =" + count);

		} catch (Exception e) {
			e.printStackTrace();
		}
		return sw.toString();
	}

	/*
	 * It returns the nodelist for a certain xpath in an xml file or a string
	 * xml. If a file is passed then isFileName is true else false.
	 */
	private static ArrayList<Object> getNodeList(String textXml,
			String xpathStr, boolean isFilename) {
		ArrayList<Object> resArrList = new ArrayList<Object>();
		Document doc = null;
		NodeList nl = null;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory
					.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			if (isFilename) {
				doc = builder.parse(new File(textXml));
			} else {
				InputSource sourceXML = new InputSource(new StringReader(
						textXml));
				doc = builder.parse(sourceXML);
			}

			doc.getDocumentElement().normalize();

			TransformerFactory tFactory = TransformerFactory.newInstance();
			Transformer transformer = tFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
					"yes");
			XPath xpath = XPathFactory.newInstance().newXPath();
			XPathExpression expr = xpath.compile(xpathStr);
			Object exprResult = expr.evaluate(doc, XPathConstants.NODESET);
			nl = (NodeList) exprResult;
			resArrList.add(nl);
			resArrList.add(doc);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return resArrList;
	}

	private String getCube(String fileName, final String cubeNameToSearch,
			final String catalogNameToSearch) {
		try {
			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();
			boolean searchForCatalog = false;
			DefaultHandler handler = new DefaultHandler() {
				boolean bCatalog = false;
				boolean bDefinition = false;
				boolean bDataSourceInfo = false;
				boolean isGlobalDataSourcePresent = false;
				StringBuilder globalDataSourceInfo = new StringBuilder(); // Had
																			// to
																			// use
																			// this
																			// as
																			// sax
																			// parser
																			// escapes
																			// the
																			// special
																			// strings
																			// and
																			// visits
																			// each
																			// part
																			// of
																			// the
																			// string.
				StringBuilder localDataSourceInfo = new StringBuilder();
				String catalogName = "";

				public void startElement(String uri, String localName,
						String qName, Attributes attributes)
						throws SAXException {
					if (qName.equalsIgnoreCase("Catalog")) {
						catalogName = attributes.getValue("name");
						LOGGER.info("catalog names =" + catalogName);
						LOGGER.info("Attributes = "
								+ attributes.getValue("name"));
						if (catalogNameToSearch == null
								|| "".equalsIgnoreCase(catalogNameToSearch)) {
							bCatalog = true;
						} else if (catalogNameToSearch
								.equalsIgnoreCase(catalogName)) {
							bCatalog = true;
						}
					}
					if (qName.equalsIgnoreCase("Definition")) {
						bDefinition = true;
					}
					if (qName.equalsIgnoreCase("DataSourceInfo")) {
						bDataSourceInfo = true;
					}

				}

				public void endElement(String uri, String localName,
						String qName) throws SAXException {
					if (qName.equalsIgnoreCase("DataSourceInfo")) {
						bDataSourceInfo = false;
						if ("".equalsIgnoreCase(catalogName)) { // Checks
																// whether it is
																// a global
																// datasourceinfo
																// or not as
																// ...if it is a
																// global none
																// of the
																// catalogs are
																// reached hence
																// the name will
																// be empty.

							isGlobalDataSourcePresent = true;
						}
					}

					if (qName.equalsIgnoreCase("Catalog")) {
						bCatalog = false;
						// LOGGER.info("Before deleting ....datasourceinfo ="
						// + localDataSourceInfo);
						if (localDataSourceInfo.length() > 0) {
							localDataSourceInfo = localDataSourceInfo.delete(0,
									localDataSourceInfo.length());
						}
					}

					if (qName.equalsIgnoreCase("Definition")) {
						bDefinition = false;
					}
				}

				public void characters(char ch[], int start, int length)
						throws SAXException {
					if (bCatalog) {
						String tempStr = new String(ch, start, length);
						tempStr = tempStr.replaceAll("\n", "").trim();
					}
					if (bDefinition) {
						if (catalogNameToSearch != null
								&& !"".equalsIgnoreCase(catalogNameToSearch)) {
							if (bCatalog) {
								String defStr = new String(ch, start, length);
								defStr = defStr.replaceAll("\n", "").trim();
								LOGGER.info("Definition : " + defStr);
								String tempOutput = "";
								try {
									if ("".equalsIgnoreCase(cubeNameToSearch)) {
										tempOutput = parseCubeXml(defStr, "");
									} else {
										tempOutput = parseCubeXml(defStr,
												cubeNameToSearch);
									}
									if (!"".equalsIgnoreCase(tempOutput)) {
										if (localDataSourceInfo.length() != 0) {
											tempOutput = "<Catalog name=\""
													+ catalogName
													+ "\" datasourceinfo=\""
													+ localDataSourceInfo
															.toString()
															.replaceAll("&",
																	"&#38;")
													+ "\">" + tempOutput
													+ "</Catalog>";
										} else {
											tempOutput = "<Catalog name=\""
													+ catalogName
													+ "\" datasourceinfo=\""
													+ globalDataSourceInfo
															.toString()
															.replaceAll("&",
																	"&#38;")
													+ "\">" + tempOutput
													+ "</Catalog>";
										}
									} else {
										if ((cubeNameToSearch == null || cubeNameToSearch
												.equalsIgnoreCase(""))
												&& (catalogNameToSearch != null && !catalogNameToSearch
														.equalsIgnoreCase(""))) {
											if (localDataSourceInfo.length() != 0) {
												tempOutput = "<Catalog name=\""
														+ catalogName
														+ "\" datasourceinfo=\""
														+ localDataSourceInfo
																.toString()
																.replaceAll(
																		"&",
																		"&#38;")
														+ "\">" + tempOutput
														+ "</Catalog>";
											} else {
												tempOutput = "<Catalog name=\""
														+ catalogName
														+ "\" datasourceinfo=\""
														+ globalDataSourceInfo
																.toString()
																.replaceAll(
																		"&",
																		"&#38;")
														+ "\">" + tempOutput
														+ "</Catalog>";
											}
										}
									}
									result = result + tempOutput;
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						} else {
							String defStr = new String(ch, start, length);
							defStr = defStr.replaceAll("\n", "").trim();
							LOGGER.info("Definition : " + defStr);
							String tempOutput = "";
							try {
								if ("".equalsIgnoreCase(cubeNameToSearch)) {
									tempOutput = parseCubeXml(defStr, "");
								} else {
									tempOutput = parseCubeXml(defStr,
											cubeNameToSearch);
								}
								if (!"".equalsIgnoreCase(tempOutput)) {
									if (localDataSourceInfo.length() != 0) {
										tempOutput = "<Catalog name=\""
												+ catalogName
												+ "\" datasourceinfo=\""
												+ localDataSourceInfo
														.toString().replaceAll(
																"&", "&#38;")
												+ "\">" + tempOutput
												+ "</Catalog>";
									} else {
										tempOutput = "<Catalog name=\""
												+ catalogName
												+ "\" datasourceinfo=\""
												+ globalDataSourceInfo
														.toString().replaceAll(
																"&", "&#38;")
												+ "\">" + tempOutput
												+ "</Catalog>";
									}
								} else {
									if ((cubeNameToSearch == null || cubeNameToSearch
											.equalsIgnoreCase(""))
											&& (catalogNameToSearch != null && !catalogNameToSearch
													.equalsIgnoreCase(""))) {
										if (localDataSourceInfo.length() != 0) {
											tempOutput = "<Catalog name=\""
													+ catalogName
													+ "\" datasourceinfo=\""
													+ localDataSourceInfo
															.toString()
															.replaceAll("&",
																	"&#38;")
													+ "\">" + tempOutput
													+ "</Catalog>";
										} else {
											tempOutput = "<Catalog name=\""
													+ catalogName
													+ "\" datasourceinfo=\""
													+ globalDataSourceInfo
															.toString()
															.replaceAll("&",
																	"&#38;")
													+ "\">" + tempOutput
													+ "</Catalog>";
										}
									}
								}
								result = result + tempOutput;
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}

					if (bDataSourceInfo) {
						if (bCatalog && isGlobalDataSourcePresent) {
							String localDataSourceInfoTemp = new String(ch,
									start, length);
							localDataSourceInfoTemp = localDataSourceInfoTemp
									.replaceAll("\n", "").trim();
							localDataSourceInfo.append(localDataSourceInfoTemp);
							System.out.println("local......."
									+ localDataSourceInfo);
						} else if (!isGlobalDataSourcePresent) {
							String globalDataSourceInfoTemp = new String(ch,
									start, length);
							globalDataSourceInfoTemp = globalDataSourceInfoTemp
									.replaceAll("\n", "").trim();
							globalDataSourceInfo
									.append(globalDataSourceInfoTemp);
							System.out.println("global......."
									+ globalDataSourceInfo);
						}
					}

				}
			};
			saxParser.parse(fileName, handler);
		} catch (Exception e) {
			e.printStackTrace();
		}
		result = wrapperOutput(result);
		return result;
	}

	private static String wrapperOutput(String output) {
		String finalResult = "";
		if ("".equalsIgnoreCase(output)) {
			finalResult = "<output>No Cubes found.</output>";
		} else {
			finalResult = "<output>" + output + "</output>";
		}
		return finalResult;
	}

	private String addCube(String inputXml, String catalogName, String cubeName) {
		String result = "";
		StreamResult resultStream = null;
		StringWriter sw = null;
		DOMSource source = null;
		Document doc = null;
		NodeList nodeList = null;

		try {
			ArrayList<Object> arrList = getNodeList(inputXml, "Schema", false);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}
			for (int x = 0; x < nodeList.getLength(); x++) {
				Node e = nodeList.item(x);
				NodeList nl = e.getChildNodes();
				List<String> cubeDefinitionElems = new ArrayList<String>();
				LOGGER.debug(" Length of the node list =" + nl.getLength());
				if (nl.getLength() > 0) {
					for (int i = 0; i < nl.getLength(); i++) {

						Node ni = nl.item(i);
						LOGGER.debug(" Name of the node inside the nodelist ="
								+ ni.getNodeName());
						if (ni.getNodeName().equalsIgnoreCase("Cube")) {
							NamedNodeMap nmapAttr = ni.getAttributes();
							String cubeNameFromXml = nmapAttr.getNamedItem(
									"name").getNodeValue();
							if ("".equalsIgnoreCase(cubeNameFromXml)) {
								return "Invalid Input Request | Cube name is missing";
							} else if (!cubeName
									.equalsIgnoreCase(cubeNameFromXml)) {
								return "Invalid Input Request | Cube name in the URL and xml do not match";
							}
						}

						TransformerFactory tFactory = TransformerFactory
								.newInstance();
						Transformer transformer = tFactory.newTransformer();
						transformer.setOutputProperty(
								OutputKeys.OMIT_XML_DECLARATION, "yes");
						sw = new StringWriter();
						source = new DOMSource(ni);
						resultStream = new StreamResult(sw);
						transformer.transform(source, resultStream);
						if (!sw.toString().trim().equalsIgnoreCase("")) {
							cubeDefinitionElems.add(sw.toString());
						}
					}

					String isPresentStr = isCubePresent(cubeName, catalogName);
					String isPresentStrArr[] = isPresentStr.split("\\|");
					String isCubeStr = isPresentStrArr[0];
					String isCatalogStr = isPresentStrArr[1];
					LOGGER.info("result: isCubePresent:" + isCubeStr
							+ ", isCatatalogPresent:" + isCatalogStr);

					if ("true".equals(isCubeStr) && "true".equals(isCatalogStr)) {
						LOGGER.debug("Cube and catalog are present : OverwritingScenario");
						boolean isCubeAddedSuccessfully = addCubeDefinitionInCatalogXml(
								cubeName, cubeDefinitionElems);
						if (isCubeAddedSuccessfully) {
							result = "Cube modified successfully";
						} else {
							result = "Cube addition failed due to internal error.";
						}

					} else if ("true".equals(isCatalogStr)
							&& "false".equals(isCubeStr)) {
						LOGGER.info("Catalog present but the Cube: " + cubeName
								+ "is not.");
						boolean isCubeAddedSuccessfully = addCubeDefinitionInCatalogXml(
								cubeName, cubeDefinitionElems);
						if (isCubeAddedSuccessfully) {
							result = "Cube is successfully added to an existing catalog.";
						} else {
							result = "Cube addition failed due to internal error.";
						}

					} else {
						LOGGER.info("Both catalog and cube does not exist.");
						result = "Catalog was not found hence cube addition failed.";
					}
					isPresentCubeHand = false;
					isPresentCubeHand = false;
				} else {
					return "Invalid Input Request | Elements: DataSource and Cube are mandatory";
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			result = e.getMessage();
		}

		return result;
	}

	private static boolean createCatalogDefinition(String catalogName,
			String catalogFileName, String cubeInfo) {
		boolean succCreation = false;
		try {
			cubeInfo = "<Schema name=\"" + catalogName + "\">" + cubeInfo
					+ "</Schema>";
			createFile(catalogFileName, cubeInfo);
			succCreation = true;

		} catch (Exception e) {
			e.printStackTrace();
		}

		return succCreation;
	}

	/*
	 * This method adds a catalog definition to the datasource file.
	 */
	private static String addCatalogInDataSource(String catalogName,
			String dataSourceInfo) throws Exception {
		String userDir = System.getProperty("user.dir");
		String userDirArr[] = userDir.split("/");
		String dirCatalogFiles = "/";
		for (String folderName : userDirArr) {
			if (folderName != null && !"".equalsIgnoreCase(folderName)) {
				dirCatalogFiles = dirCatalogFiles + folderName + "/";
				if (folderName.contains("apache")) {
					break;
				}
			}
		}

		dirCatalogFiles = dirCatalogFiles + "webapps/mondrian/WEB-INF/queries/";

		String newCatalogDefFileName = dirCatalogFiles + catalogName + ".xml";
		Document doc = null;
		NodeList nodeList = null;
		try {

			dataSourceInfo = dataSourceInfo.trim() + "Catalog=file:"
					+ newCatalogDefFileName + ";";
			LOGGER.debug("Final datasource info getting added = "
					+ dataSourceInfo);

			ArrayList<Object> arrList = getNodeList(DATASOURCE_PATH,
					"DataSources/DataSource/Catalogs", true);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}

			if (nodeList.getLength() > 0) {
				Node catalogsNode = nodeList.item(0);
				String xmlForCatalog = "<Catalog name=\"" + catalogName
						+ "\"><DataSourceInfo>"
						+ dataSourceInfo.replaceAll("&", "&#38;")
						+ "</DataSourceInfo><Definition>"
						+ newCatalogDefFileName + "</Definition></Catalog>";

				Element e1 = createDOM(xmlForCatalog);

				Node importedNode = doc.importNode(e1, true);
				catalogsNode.appendChild(importedNode);

			}

			StreamResult resultStreamNew = new StreamResult(new StringWriter());
			TransformerFactory tFactoryNew = TransformerFactory.newInstance();
			Transformer transformerNew = tFactoryNew.newTransformer();
			transformerNew.setOutputProperty("indent", "yes");
			transformerNew.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
					"yes");
			DOMSource sourceNew = new DOMSource(doc);
			transformerNew.transform(sourceNew, resultStreamNew);
			String finalXml = resultStreamNew.getWriter().toString();
			deleteFile(DATASOURCE_PATH);
			createFile(DATASOURCE_PATH, finalXml);

		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
		return newCatalogDefFileName;
	}

	private static boolean addCubeDefinitionInCatalogXml(String cubeName,
			List<String> cubeDefinitionElemList) {
		boolean isSucess = false;
		Document doc = null;
		NodeList nodeList = null;
		LOGGER.info("Cubeinfo=" + catalogFileForAdd);
		try {
			ArrayList<Object> arrList = getNodeList(catalogFileForAdd,
					"Schema", true);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}
			if (nodeList.getLength() == 1) {
				NodeList nl = nodeList.item(0).getChildNodes();

				if (nl.getLength() > 0) {
					Node n = nl.item(0);
					Node parent = n.getParentNode();
					for (String elem : cubeDefinitionElemList) {

						Element e1 = createDOM(elem);
						Node alreadyExistingNode = getAlreadyExistingNode(e1,
								nl);
						Node importedNode = doc.importNode(e1, true);
						if (alreadyExistingNode != null) {
							// Replace the existing node with append the current
							// node
							parent.replaceChild(importedNode,
									alreadyExistingNode);
						} else {
							// Append the current node
							parent.appendChild(importedNode);
						}
					}
					
				} else {
					LOGGER.debug("Adding a cube definition first time to a catalog. Received elements size = "
							+ cubeDefinitionElemList.size());
					Node parent = nodeList.item(0);
					// First time adding to a catalog
					// Append the elements to the schema
					for (String elemStr : cubeDefinitionElemList) {
						if (!elemStr.equalsIgnoreCase("")) {
							Element e1 = createDOM(elemStr);
							Node importedNode = doc.importNode(e1, true);
							parent.appendChild(importedNode);
						}
					}
				}
				StreamResult resultStreamNew = new StreamResult(
						new StringWriter());
				TransformerFactory tFactoryNew = TransformerFactory
						.newInstance();
				Transformer transformerNew = tFactoryNew.newTransformer();
				transformerNew.setOutputProperty(
						OutputKeys.OMIT_XML_DECLARATION, "yes");
				DOMSource sourceNew = new DOMSource(doc);
				transformerNew.transform(sourceNew, resultStreamNew);
				String finalXml = resultStreamNew.getWriter().toString();
				LOGGER.info("Final XML which gets written to the catalog definition file-----------"
						+ finalXml);
				if (deleteFile(catalogFileForAdd) && createFile(catalogFileForAdd, finalXml)) {
					isSucess = true;
				} 
				catalogFileForAdd = "";
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return isSucess;
	}

	/*
	 * Checks the presence of an element be using the name.
	 */

	private static Node getAlreadyExistingNode(Element elem, NodeList nl) {
		Node alreadyExisitingNode = null;
		LOGGER.debug("Checking the element:: " + elem.getNodeName()
				+ " with name as:: " + elem.getAttribute("name"));
		if (elem.getNodeName().equalsIgnoreCase("#text")) {
			LOGGER.debug("No point in checking for #text");
			return alreadyExisitingNode;
		}
		for (int i = 0; i < nl.getLength(); i++) {
			if (nl.item(i).getAttributes() != null
					&& nl.item(i).getAttributes().getNamedItem("name") != null) {
				LOGGER.debug("Node name already in the xml"
						+ nl.item(i).getNodeName());
				LOGGER.debug("Checking the name for:: "
						+ nl.item(i).getNodeName()
						+ " as::"
						+ nl.item(i).getAttributes().getNamedItem("name")
								.getNodeValue());
				if (elem.getNodeName().equalsIgnoreCase(
						nl.item(i).getNodeName())
						&& elem.getAttribute("name").equalsIgnoreCase(
								nl.item(i).getAttributes().getNamedItem("name")
										.getNodeValue())) {
					LOGGER.debug("The element is already present");
					alreadyExisitingNode = nl.item(i);
				}
			}

		}
		return alreadyExisitingNode;
	}

	public static final Element createDOM(String strXML)
			throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		InputSource sourceXML = new InputSource(new StringReader(strXML));
		Document xmlDoc = db.parse(sourceXML);
		Element e = xmlDoc.getDocumentElement();
		e.normalize();
		return e;
	}

	/*
	 * Returns String containing if the cube is present or the catalog is
	 * present in the form of "result_cube|result_catalog" e.g "true|true" if
	 * both cube and catalog is present. "false|true" if cube is not present but
	 * catalog is present.
	 */
	private String isCubePresent(String cubeName, String catalogName) {
		LOGGER.info("Inside in isCubePresent");
		String isPresent = "false|false";
		isPresentCubeHand = false;
		isPresentCatalogHand = false;

		final String catalogNameTemp = catalogName;
		final String cubeNameTemp = cubeName;
		try {

			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();

			DefaultHandler handler = new DefaultHandler() {

				boolean bCatalog = false;
				boolean bDefinition = false;
				String catalogName = "";

				public void startElement(String uri, String localName,
						String qName, Attributes attributes)
						throws SAXException {

					if (qName.equalsIgnoreCase("Catalog")) {
						catalogName = attributes.getValue("name");
						if (catalogName.equalsIgnoreCase(catalogNameTemp)) {
							LOGGER.info("Attributes = "
									+ attributes.getValue("name"));
							isPresentCatalogHand = true;
							bCatalog = true;
						}
					}
					if (qName.equalsIgnoreCase("Definition") && bCatalog) {
						bDefinition = true;
					}
				}

				public void endElement(String uri, String localName,
						String qName) throws SAXException {
				}

				public void characters(char ch[], int start, int length)
						throws SAXException {

					if (bDefinition) {
						String defStr = new String(ch, start, length);
						defStr = defStr.replaceAll("\n", "").trim();
						LOGGER.info("Definition : " + defStr);
						String tempOutput = parseCubeXml(defStr, cubeNameTemp);
						if (!"".equalsIgnoreCase(tempOutput)) {
							LOGGER.info("Cube exists in Inside");
							isPresentCubeHand = true;
						}
						catalogFileForAdd = defStr;
						bDefinition = false;
						bCatalog = false;
					}

				}

			};
			saxParser.parse(DATASOURCE_PATH, handler);
			LOGGER.info("Value of isPresentCubeHand =" + isPresentCubeHand);
			LOGGER.info("Value of isPresentCatalogHand ="
					+ isPresentCatalogHand);
			if (isPresentCubeHand && isPresentCatalogHand) {
				isPresent = "true|true";
			} else if (!isPresentCubeHand && isPresentCatalogHand) {
				isPresent = "false|true";
			} else {
				isPresent = "false|false";
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return isPresent;
	}

	/*
	 * Creates a file with the contents provided
	 */
	private static boolean createFile(String fileName, String contents)
			throws Exception {
		boolean createSucc = false;
		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new FileWriter(new File(fileName)));
			bw.write(contents);
			bw.flush();
			createSucc = true;
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (bw != null) {
				bw.close();
			}
		}
		return createSucc;
	}

	private boolean deleteCube(String cubeName, String catalogName)
			throws Exception {
		StreamResult resultStream = null;
		StringWriter sw = new StringWriter();
		String cubeNameFromXml = "";
		boolean deleteSucc = false;
		Document doc = null;
		NodeList nodeList = null;
		String resStr = isCubePresent(cubeName, catalogName);
		String resStrArr[] = resStr.split("\\|");
		LOGGER.info("result from iscubepresent = " + resStr);
		if ("false".equalsIgnoreCase(resStrArr[1])) {
			throw new Exception("The catalog could not be found");
		} else if ("false".equalsIgnoreCase(resStrArr[0])
				&& "true".equalsIgnoreCase(resStrArr[1])) {
			throw new Exception("The cube could not be found");
		}

		String catalogFileName = catalogFileForAdd;
		catalogFileForAdd = "";
		try {

			ArrayList<Object> arrList = getNodeList(catalogFileName, "Schema",
					true);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}
			resultStream = new StreamResult(sw);
			LOGGER.info("Length for schema length=" + nodeList.getLength());
			for (int x = 0; x < nodeList.getLength(); x++) {
				Node e = nodeList.item(x);
				NodeList nl = e.getChildNodes();
				LOGGER.info("Length of the list =" + nl.getLength());
				for (int i = 0; i < nl.getLength(); i++) {
					Node n = nl.item(i);
					NamedNodeMap nmapAttr = n.getAttributes();
					if ("Cube".equalsIgnoreCase(n.getNodeName())) {
						cubeNameFromXml = nmapAttr.getNamedItem("name")
								.getNodeValue();

						if (cubeName.equalsIgnoreCase(cubeNameFromXml)) {
							e.removeChild(n);
							TransformerFactory tFactory = TransformerFactory
									.newInstance();
							Transformer transformer = tFactory.newTransformer();
							transformer.setOutputProperty(
									OutputKeys.OMIT_XML_DECLARATION, "yes");
							DOMSource sourceNew = new DOMSource(doc);
							transformer.transform(sourceNew, resultStream);
							String finalXml = resultStream.getWriter()
									.toString();
							LOGGER.info(finalXml);

							NodeList cubesList = e.getChildNodes();
							boolean anyMoreCubesPresent = false;
							for (int j = 0; j < nl.getLength(); j++) {
								Node nlNode = nl.item(j);
								if (nlNode.getNodeName().equalsIgnoreCase(
										"Cube")) {
									anyMoreCubesPresent = true;
									break;
								}
							}
							if (anyMoreCubesPresent) {
								deleteFile(catalogFileName);
								createFile(catalogFileName, finalXml);
							} else {
								deleteFile(catalogFileName);
								deleteCatalogFromDatasource(catalogName);
							}
							deleteSucc = true;
							break;

						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return deleteSucc;
	}

	private boolean deleteCatalogFromDatasource(String catalogName) {
		boolean delSucc = false;
		Document doc = null;
		NodeList nodeList = null;
		try {
			ArrayList<Object> arrList = getNodeList(DATASOURCE_PATH,
					"DataSources/DataSource/Catalogs/Catalog", true);
			for (Object o : arrList) {
				if (o instanceof Document) {
					doc = (Document) o;
				} else if (o instanceof NodeList) {
					nodeList = (NodeList) o;
				}
			}

			if (nodeList.getLength() > 0) {
				for (int x = 0; x < nodeList.getLength(); x++) {
					Node n = nodeList.item(x);
					NamedNodeMap nmap = n.getAttributes();
					String catalogNameFromXml = nmap.getNamedItem("name")
							.getNodeValue();
					if (catalogName.equalsIgnoreCase(catalogNameFromXml)) {
						Node parent = n.getParentNode();
						parent.removeChild(n);
						delSucc = true;
						break;
					}
				}
			}

			StreamResult resultStreamNew = new StreamResult(new StringWriter());
			TransformerFactory tFactoryNew = TransformerFactory.newInstance();
			Transformer transformerNew = tFactoryNew.newTransformer();
			transformerNew.setOutputProperty("indent", "yes");
			transformerNew.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
					"yes");
			DOMSource sourceNew = new DOMSource(doc);
			transformerNew.transform(sourceNew, resultStreamNew);
			String finalXml = resultStreamNew.getWriter().toString();
			deleteFile(DATASOURCE_PATH);
			createFile(DATASOURCE_PATH, finalXml);

		} catch (Exception e) {
			e.printStackTrace();
		}
		return delSucc;
	}

	private static boolean checkCatalogInDataSource(String catalogName)
			throws Exception {
		Document doc = null;
		NodeList nodeList = null;
		ArrayList<Object> arrList = getNodeList(DATASOURCE_PATH,
				"DataSources/DataSource/Catalogs/Catalog", true);
		for (Object o : arrList) {
			if (o instanceof Document) {
				doc = (Document) o;
			} else if (o instanceof NodeList) {
				nodeList = (NodeList) o;
			}
		}

		if (nodeList.getLength() > 0) {
			for (int x = 0; x < nodeList.getLength(); x++) {
				Node n = nodeList.item(x);
				NamedNodeMap nmap = n.getAttributes();
				String catalogNameFromXml = nmap.getNamedItem("name")
						.getNodeValue();
				if (catalogName.equalsIgnoreCase(catalogNameFromXml)) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean deleteFile(String fileName) throws Exception {
		boolean deleteSucc = false;
		try {

			File file = new File(fileName);
			file.delete();
			deleteSucc = true;

		} catch (Exception e) {
			e.printStackTrace();
		}
		return deleteSucc;
	}

}
