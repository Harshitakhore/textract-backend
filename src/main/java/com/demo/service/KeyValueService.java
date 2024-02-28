package com.demo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map; // Import the Map interface

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

/*import com.example.demo.entity.ExpenseData;
import com.example.demo.repository.ExpenseDataRepository;
*/
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;
import software.amazon.awssdk.services.textract.model.ExpenseField;
import software.amazon.awssdk.services.textract.model.LineItemFields;
import software.amazon.awssdk.services.textract.model.LineItemGroup;

@Service

public class KeyValueService {
//	@Autowired(required=true)
//	private ExpenseDataRepository expenseDataRepository;

	private final TextractClient textractClient;
	
	//  @Autowired(required=true) private ExpenseData expenseData;
	 

	public KeyValueService() {

		this.textractClient = TextractClient.builder().region(Region.US_EAST_1).build();
	}
	// public KeyValueService(ExpenseDataRepository repo) {}

	public List<Map<String, String>> analyzeExpense(File imageFile) throws IOException {
		BufferedImage image = ImageIO.read(imageFile);
		AnalyzeExpenseRequest request = AnalyzeExpenseRequest.builder()
				.document(Document.builder().bytes(SdkBytes.fromByteArray(imageToBytes(image))).build()).build();
		AnalyzeExpenseResponse result = textractClient.analyzeExpense(request);
		HashMap<String, String> expenseMap = new HashMap<>();
		return saveAnalyzedData(result, expenseMap);
		/* return expenseMap; */
	}

	private byte[] imageToBytes(BufferedImage image) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", baos);
		return baos.toByteArray();
	}

	private  List<Map<String, String>> saveAnalyzedData(AnalyzeExpenseResponse response, HashMap<String, String> expenseMap) {
		List<Map<String, String>> expenseListOfMap = new ArrayList<>();
		for (ExpenseDocument expenseDocument : response.expenseDocuments()) {
			if (expenseDocument.hasLineItemGroups()) {
				for (LineItemGroup lineItemGroup : expenseDocument.lineItemGroups()) {
					if (lineItemGroup.hasLineItems()) {
						for (LineItemFields lineItemField : lineItemGroup.lineItems()) {
							if (lineItemField.hasLineItemExpenseFields()) {
								for (ExpenseField expenseField : lineItemField.lineItemExpenseFields()) {
									if (expenseField.labelDetection() != null
											&& expenseField.valueDetection() != null) {
										String label = expenseField.labelDetection().text().toString();
										String value = expenseField.valueDetection().text().toString();
										if (label != null && value != null) {
											if(label.equals(".Price")) {
												label="L.Price";
											}
											if(label.equals("[HSN Code:") || label.equals("[HSN Code") || label.equals("ONLY") || value.equals("33049990]")) {
												
											}else {
											expenseMap.put(label, value);
											}
											for (Map.Entry<String, String> entry : expenseMap.entrySet()) {
												
											}

										}

										
									}
								}

							}
							HashMap<String, String> temp = new HashMap<>();
							for (String key : expenseMap.keySet()) {
								System.out.println(key + "    " + expenseMap.get(key));
								temp.put(key, expenseMap.get(key));
							}
							System.out.println();
							expenseListOfMap.add(temp);
						}
					}
				}
			}
		}
			return expenseListOfMap;
	}
}