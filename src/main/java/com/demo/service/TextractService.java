package com.demo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.DetectDocumentTextRequest;
import com.amazonaws.services.textract.model.DetectDocumentTextResult;
import com.amazonaws.services.textract.model.Document;
import com.demo.util.PDFUtils;
import com.demo.vo.ExtractedFieldsDataVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

@Service
public class TextractService {
	int i = 0;
	@Autowired
	private AmazonTextract textractClient;

	@Autowired
	PDFUtils pdfUtils;

	public List<Map<String, String>> getTextFromCroppedPdf(MultipartFile file) {
		List<Map<String, String>> extractedFieldsList = new ArrayList<>();
		try (PDDocument doc = PDDocument.load((file).getInputStream())) {
			
			List<String> coordinates = extractCoordinates(doc);
			if (!coordinates.isEmpty()) {
				Document document = createDocumentFromPage(doc);

				// Create request to detect document text
				DetectDocumentTextRequest request = new DetectDocumentTextRequest().withDocument(document);

				// Call AWS Textract service
				DetectDocumentTextResult result = textractClient.detectDocumentText(request);

				// Extract text blocks and parse into fields for each page
				Map<String, String> extractedFields = extractFieldsFromBlocks(result.getBlocks(), i);
				i++;
				extractedFieldsList.add(extractedFields);

				// }
			}
			
			doc.close();
		} catch (IOException e) {
			throw new RuntimeException("Error extracting fields from PDF", e);
		}

		return extractedFieldsList;
	}

	public List<String> extractCoordinates(PDDocument page) throws IOException {
		List<String> coordinates = new ArrayList<>();
		PDFTextStripper stripper = new PDFTextStripper() {
			@Override
			protected void processTextPosition(TextPosition text) {
				String coordinate = String.format("String[%f,%f fs=%f xscale=%f height=%f space=%f width=%f] %s",
						text.getXDirAdj(), text.getYDirAdj(), text.getFontSize(), text.getXScale(), text.getHeightDir(),
						text.getWidthOfSpace(), text.getWidthDirAdj(), text.getCharacterCodes());
				coordinates.add(coordinate);
			}
		};
		stripper.setSortByPosition(true);
		stripper.getText(page);
		return coordinates;
	}

	public Document createDocumentFromPage(PDDocument page) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		page.save(baos);
		byte[] pageBytes = baos.toByteArray();
		ByteBuffer imageBytes = ByteBuffer.wrap(pageBytes);
		return new Document().withBytes(imageBytes);
	}

	public Map<String, String> extractFieldsFromBlocks(List<Block> blocks, int i) {
		Map<String, String> extractedFields = new HashMap<>();
		String[] KeysOfPdfPurchaseOrder = { "Ship To", "Phone", "Fax", "Email", "Buyer", "Vendor FSSAI No", "Validity",
				"CIN", "GSTIN", "PO #", "PO Date", "Delivery Dt", "Vendor", "Phone", "Email", "Attn", "GSTIN"};
		String output = "";
		for (Block block : blocks) {
			if ("LINE".equals(block.getBlockType())) {
				String parts = block.getText();
				
				parts = parts.replace(KeysOfPdfPurchaseOrder[i], " ");
				String partsNew = parts.replaceAll("\\R", " ");
				output = output + partsNew;
			}
		}

		if (i == 13) {
			extractedFields.put("PO Phone", output);
		} else if (i == 14) {
			extractedFields.put("PO Email", output);
		} else if (i == 16) {
			extractedFields.put("PO GSTIN", output);
		} else {
			extractedFields.put(KeysOfPdfPurchaseOrder[i], output);
		}

//		System.out.println(KeysOfPdfPurchaseOrder[i]+" "+i);
		return extractedFields;
	}
	



	public String cropPDFMM(float x, float y, float width, float height, String srcFilePath) throws IOException {

		// helper functions to convert between mm <-> units
		Function<Float, Float> mmToUnits = (Float a) -> a / 0.352778f;
		Function<Float, Float> unitsToMm = (Float a) -> a * 0.352778f;

		// convert mm to units
		float xUnits = mmToUnits.apply(x);
		float yUnits = mmToUnits.apply(y);
		float widthUnits = mmToUnits.apply(width);
		float heightUnits = mmToUnits.apply(height);

		// extract the doc's file name
		File srcFile = new File(srcFilePath);
		String fileName = srcFile.getName();
		int dotIndex = fileName.lastIndexOf('.');
		String fileNameWithoutExtension = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);
		System.out.println("fileNameWithoutExtension##" + fileNameWithoutExtension);

		// crop each page
		PDDocument doc = PDDocument.load(srcFile);
		int nrOfPages = doc.getNumberOfPages();
		PDRectangle newBox = new PDRectangle(xUnits, yUnits, widthUnits, heightUnits);
		for (int i = 0; i < nrOfPages; i++) {
			doc.getPage(i).setCropBox(newBox);
		}

		
		// save the result & append -cropped to the file name
		File outFile = new File(srcFilePath + fileNameWithoutExtension + "-cropped.pdf"); //
		doc.save(outFile);
		doc.close();

		System.out.println(outFile.getCanonicalPath());

		return outFile.getCanonicalPath();

	}


	
	public List<Map<String, String>> getInputFileThenConverToMap(String filePath) throws IOException {

		File file = new File(filePath);

		// Read the file content into a byte array
		FileInputStream input = new FileInputStream(file);
		byte[] bytes = new byte[(int) file.length()];
		input.read(bytes);
		input.close();

		// Create a MultipartFile object from the byte array
		MultipartFile multipartFile = new MockMultipartFile(file.getName(), file.getName(), "text/plain", bytes);

		
		// System.out.println("File created successfully: " + multipartFile.getOriginalFilename());
		List<Map<String, String>> listOfMap = new ArrayList<>();
		listOfMap = getTextFromCroppedPdf(multipartFile);
		for (Map<String, String> extractedFields : listOfMap) {
			for (Map.Entry<String, String> entry : extractedFields.entrySet()) {
				System.out.println(entry.getKey() + ": " + entry.getValue());
			}
			System.out.println(); 
		}

		return listOfMap;

	}

	public static String mergePDFs(String filePath1, String filePath2, String mergedFilePath) throws IOException {
		PDFMergerUtility merger = new PDFMergerUtility();

		List<InputStream> inputStreams = new ArrayList<>();
		inputStreams.add(new FileInputStream(filePath1));
		inputStreams.add(new FileInputStream(filePath2));

		merger.addSources(inputStreams);
		merger.setDestinationFileName(mergedFilePath);
		merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());

		for (InputStream inputStream : inputStreams) {
			inputStream.close(); // Close all input streams after merging
		}
		return mergedFilePath;
	}

	public Map<String, String> cropPdfImage_forMultiPage(String path) {
		String srcFilePath = path;
		PDFUtils app = new PDFUtils();

		Map<String, String> newMap = new HashMap<>();
		i=0;
		try {


			String resultFilePath1 = app.cropPDFMM(0f, 183f, 100f, 200f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_1 = new ArrayList<>();
			listOfMap_in_output_1 = getInputFileThenConverToMap(resultFilePath1);

			String resultFilePath2 = app.cropPDFMM(0f, 178f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_2 = new ArrayList<>();
			listOfMap_in_output_2 = getInputFileThenConverToMap(resultFilePath2);

			String resultFilePath3 = app.cropPDFMM(0f, 174f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_3 = new ArrayList<>();
			listOfMap_in_output_3 = getInputFileThenConverToMap(resultFilePath3);

			String resultFilePath4 = app.cropPDFMM(0f, 170f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_4 = new ArrayList<>();
			listOfMap_in_output_4 = getInputFileThenConverToMap(resultFilePath4);

			String resultFilePath5 = app.cropPDFMM(0f, 165f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_5 = new ArrayList<>();
			listOfMap_in_output_5 = getInputFileThenConverToMap(resultFilePath5);

			String resultFilePath6 = app.cropPDFMM(0f, 161f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_6 = new ArrayList<>();
			listOfMap_in_output_6 = getInputFileThenConverToMap(resultFilePath6);

			String resultFilePath7 = app.cropPDFMM(0f, 157f, 100f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_7 = new ArrayList<>();
			listOfMap_in_output_7 = getInputFileThenConverToMap(resultFilePath7);

			String resultFilePath8 = app.cropPDFMM(100f, 182f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_8 = new ArrayList<>();
			listOfMap_in_output_8 = getInputFileThenConverToMap(resultFilePath8);

			String resultFilePath9 = app.cropPDFMM(100f, 176f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_9 = new ArrayList<>();
			listOfMap_in_output_9 = getInputFileThenConverToMap(resultFilePath9);

			String resultFilePath10 = app.cropPDFMM(180f, 196f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_10 = new ArrayList<>();
			listOfMap_in_output_10 = getInputFileThenConverToMap(resultFilePath10);

			String resultFilePath11 = app.cropPDFMM(180f, 191f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_11 = new ArrayList<>();
			listOfMap_in_output_11 = getInputFileThenConverToMap(resultFilePath11);

			String resultFilePath12 = app.cropPDFMM(180f, 186f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_12 = new ArrayList<>();
			listOfMap_in_output_12 = getInputFileThenConverToMap(resultFilePath12);

			String resultFilePath13 = app.cropPDFMM(180f, 170f, 80f, 15f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_13 = new ArrayList<>();
			listOfMap_in_output_13 = getInputFileThenConverToMap(resultFilePath13);

			String resultFilePath14 = app.cropPDFMM(180f, 166f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_14 = new ArrayList<>();
			listOfMap_in_output_14 = getInputFileThenConverToMap(resultFilePath14);

			String resultFilePath15 = app.cropPDFMM(180f, 161f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_15 = new ArrayList<>();
			listOfMap_in_output_15 = getInputFileThenConverToMap(resultFilePath15);

			String resultFilePath16 = app.cropPDFMM(180f, 156.5f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_16 = new ArrayList<>();
			listOfMap_in_output_16 = getInputFileThenConverToMap(resultFilePath16);

			String resultFilePath17 = app.cropPDFMM(180f, 152f, 80f, 5f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_17 = new ArrayList<>();
			listOfMap_in_output_17 = getInputFileThenConverToMap(resultFilePath17);


			System.out.println("---------------------");
			

			for (Map.Entry<String, String> entry : listOfMap_in_output_1.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_2.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_3.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_4.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_5.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_6.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_7.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_8.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_9.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_10.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_11.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_12.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_13.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_14.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_15.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_16.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_17.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}

			for (Map.Entry<String, String> entry : newMap.entrySet()) {

//				System.out.println(entry.getKey() + ": " + entry.getValue());
			}

			Gson gson = new Gson();
			String json = gson.toJson(newMap);

			System.out.println(json);

			System.out.println("Done!");
		} catch (Exception e) {
			System.out.println(e);
			System.err.println(e.getStackTrace());
			System.err.println(e.getCause());
		}
		return newMap;
	}

	public Map<String, String> cropPdfImage_forSinglePage(String path) {
		i=0;
		String srcFilePath = path;
		Map<String, String> newMap = new HashMap<>();
		PDFUtils app = new PDFUtils();
		
		try {

			String resultFilePath1 = app.cropPDFMM(0f, 0f, 27f, 100f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_1 = new ArrayList<>();
			listOfMap_in_output_1 = getInputFileThenConverToMap(resultFilePath1);

			String resultFilePath2 = app.cropPDFMM(26.5f, 0f, 5.5f, 100f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_2 = new ArrayList<>();
			listOfMap_in_output_2 = getInputFileThenConverToMap(resultFilePath2);

			String resultFilePath3 = app.cropPDFMM(30f, 0f, 5.5f, 100f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_3 = new ArrayList<>();
			listOfMap_in_output_3 = getInputFileThenConverToMap(resultFilePath3);

			String resultFilePath4 = app.cropPDFMM(34.5f, 0f, 5.5f, 110f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_4 = new ArrayList<>();
			listOfMap_in_output_4 = getInputFileThenConverToMap(resultFilePath4);

			String resultFilePath5 = app.cropPDFMM(39.5f, 0f, 5.5f, 110f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_5 = new ArrayList<>();
			listOfMap_in_output_5 = getInputFileThenConverToMap(resultFilePath5);

			String resultFilePath6 = app.cropPDFMM(44f, 0f, 5.5f, 110f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_6 = new ArrayList<>();
			listOfMap_in_output_6 = getInputFileThenConverToMap(resultFilePath6);

			String resultFilePath7 = app.cropPDFMM(48f, 0f, 5.5f, 110f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_7 = new ArrayList<>();
			listOfMap_in_output_7 = getInputFileThenConverToMap(resultFilePath7);

			String resultFilePath8 = app.cropPDFMM(24f, 110f, 5.5f, 65f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_8 = new ArrayList<>();
			listOfMap_in_output_8 = getInputFileThenConverToMap(resultFilePath8);

			String resultFilePath9 = app.cropPDFMM(28f, 110f, 5.5f, 65f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_9 = new ArrayList<>();
			listOfMap_in_output_9 = getInputFileThenConverToMap(resultFilePath9);

			String resultFilePath10 = app.cropPDFMM(9f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_10 = new ArrayList<>();
			listOfMap_in_output_10 = getInputFileThenConverToMap(resultFilePath10);

			String resultFilePath11 = app.cropPDFMM(14.5f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_11 = new ArrayList<>();
			listOfMap_in_output_11 = getInputFileThenConverToMap(resultFilePath11);

			String resultFilePath12 = app.cropPDFMM(19f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_12 = new ArrayList<>();
			listOfMap_in_output_12 = getInputFileThenConverToMap(resultFilePath12);

			String resultFilePath13 = app.cropPDFMM(24f, 180f, 15f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_13 = new ArrayList<>();
			listOfMap_in_output_13 = getInputFileThenConverToMap(resultFilePath13);

			String resultFilePath14 = app.cropPDFMM(39f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_14 = new ArrayList<>();
			listOfMap_in_output_14 = getInputFileThenConverToMap(resultFilePath14);

			String resultFilePath15 = app.cropPDFMM(43f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_15 = new ArrayList<>();
			listOfMap_in_output_15 = getInputFileThenConverToMap(resultFilePath15);

			String resultFilePath16 = app.cropPDFMM(48f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_16 = new ArrayList<>();
			listOfMap_in_output_16 = getInputFileThenConverToMap(resultFilePath16);

			String resultFilePath17 = app.cropPDFMM(52f, 180f, 5.5f, 150f, srcFilePath);
			List<Map<String, String>> listOfMap_in_output_17 = new ArrayList<>();
			listOfMap_in_output_17 = getInputFileThenConverToMap(resultFilePath17);

			
			System.out.println("---------------------");
			

			for (Map.Entry<String, String> entry : listOfMap_in_output_1.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_2.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_3.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_4.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_5.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_6.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_7.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_8.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_9.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_10.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_11.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_12.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_13.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_14.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_15.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_16.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : listOfMap_in_output_17.get(0).entrySet()) {
				newMap.put(entry.getKey(), entry.getValue());
			}

			for (Map.Entry<String, String> entry : newMap.entrySet()) {
//				System.out.println(entry.getKey() + ": " + entry.getValue());
			}

			Gson gson = new Gson();
			String json = gson.toJson(newMap);

			System.out.println(json);


			System.out.println("Done!");
		} catch (Exception e) {
			System.out.println(e);
			System.err.println(e.getStackTrace());
			System.err.println(e.getCause());
		}
		return newMap;
	}

	public ExtractedFieldsDataVo extractFieldsFromPdf(MultipartFile file) throws Exception {
	
		
		 ExtractedFieldsDataVo extractedFieldsDataVo = new ExtractedFieldsDataVo();
		    try {
		        // Create a temporary file to save the uploaded file
		        File tempFile = File.createTempFile("temp", ".pdf");
		        // Save the uploaded file to the temporary file
		        file.transferTo(tempFile);

		        PDDocument document = PDDocument.load(tempFile);
		        int totalPages = document.getNumberOfPages();
		        PDDocument cropDocument = new PDDocument();

		        if (totalPages == 1) {
		            System.out.println("Pdf is single page ");
		            

		            // getting the header data and storing in json
		            PDDocument singlePageDocument = new PDDocument();
                    PDPage page = document.getPage(0);
                    singlePageDocument.addPage(page);
                    String outputPath = tempFile.getParent() + "/page_single-page-doc" + ".pdf";
                    singlePageDocument.save(outputPath);
                    singlePageDocument.close();
                    PDPage page_one = document.getPage(0);
                    singlePageDocument.addPage(page_one);
                    extractedFieldsDataVo.setHead(cropPdfImage_forSinglePage(outputPath));
                    
                    
                    PDPage pageOne = document.getPage(0);
                    cropDocument.addPage(pageOne);
                    cropDocument.getPage(0).setCropBox(cordinates(57f, 0f, 124f, 300f));
		            
                    
                    List<List<Map<String, String>>> analyzePdfData = analyzePdf(cropDocument);
		            extractedFieldsDataVo.setAnalyzePdf(analyzePdfData);
		            
		            
		            
		            
		            
		        } else {
		            System.out.println("Pdf is multi page ");

		            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
		                // Create a new document for each page

		                if (pageNumber == 0) {
		                    PDDocument singlePageDocument = new PDDocument();
		                    PDPage page = document.getPage(pageNumber);
		                    singlePageDocument.addPage(page);
		                    String outputPath = tempFile.getParent() + "/page_" + (pageNumber + 1) + ".pdf";
		                    singlePageDocument.save(outputPath);
		                    singlePageDocument.close();
		                    extractedFieldsDataVo.setHead(cropPdfImage_forMultiPage(outputPath));
		                    PDPage pageOne = document.getPage(pageNumber);
		                    cropDocument.addPage(pageOne);
		                    cropDocument.getPage(pageNumber).setCropBox(cordinates(0f, 15f, 300f, 138f));
		                } else {
		                    PDPage pageOne = document.getPage(pageNumber);
		                    cropDocument.addPage(pageOne);
		                    cropDocument.getPage(pageNumber).setCropBox(cordinates(0f, 31.5f, 300f, 170f));
		                }
		            }
		            List<List<Map<String, String>>> analyzePdfData = analyzePdf(cropDocument);
		            extractedFieldsDataVo.setAnalyzePdf(analyzePdfData);
			}
			cropDocument.close();

			document.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Close the original document

		return extractedFieldsDataVo;

	}

	public PDRectangle cordinates(float x, float y, float width, float height) {
		Function<Float, Float> mmToUnits = (Float a) -> a / 0.352778f;
		Function<Float, Float> unitsToMm = (Float a) -> a * 0.352778f;

		// convert mm to units
		float xUnits = mmToUnits.apply(x);
		float yUnits = mmToUnits.apply(y);
		float widthUnits = mmToUnits.apply(width);
		float heightUnits = mmToUnits.apply(height);
		return new PDRectangle(xUnits, yUnits, widthUnits, heightUnits);
	}

/// ------------------------------------------------------------------
	private static final String IMAGE_OUTPUT_DIRECTORY = "C:\\Users\\HP\\OneDrive\\Desktop\\Shiavnski Docs\\myapp 21st Feb\\images";

	public List<List<Map<String, String>>> analyzePdf(PDDocument document) throws IOException {
		List<String> imagePaths = new ArrayList<>();
		List<List<Map<String, String>>> expenseListOfListOfMap = new ArrayList<>();
		HashMap<String, String> combinedExpenseMap = new HashMap<>();

		// PDDocument document = PDDocument.load(pdfFile.getInputStream())
		PDFRenderer pdfRenderer = new PDFRenderer(document);
		for (int page = 0; page < document.getNumberOfPages(); ++page) {
			BufferedImage bufferedImage = pdfRenderer.renderImageWithDPI(page, 300, ImageType.RGB);
			String imagePath = saveImage(bufferedImage, page);
			imagePaths.add(imagePath);
			File imageFile = new File(imagePath);
			KeyValueService keyValueService = new KeyValueService();
			List<Map<String, String>> expenseMap = keyValueService.analyzeExpense(imageFile);
			expenseListOfListOfMap.add(expenseMap);
			// combinedExpenseMap.putAll(expenseMap);
		}

		return expenseListOfListOfMap;
		// return convertMapToJson(combinedExpenseMap);
	}

	private String saveImage(BufferedImage image, int pageNumber) throws IOException {
		Path p = Paths.get("");
		String paths = p.toAbsolutePath().toString();

		String imagePath = paths + "\\images" + File.separator + "page123_" + (pageNumber + 1) + ".png";
		System.out.println(imagePath);
		File outputFile = new File(imagePath);
		javax.imageio.ImageIO.write(image, "png", outputFile);
		return imagePath;
	}

	private String convertMapToJson(HashMap<String, String> map) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.writeValueAsString(map);
	}
}