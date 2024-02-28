package com.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;

@Configuration
public class AwsConfiguration {

   
//    private static final String AWS_ACCESS_KEY = "AKIAW3MD7LJQPRKEOOE3";
//    private static final String AWS_SECRET_KEY = "kjcpEHZALf4YHqgUCVNWlMLfsYRIqJHiKXWQDgFQ";
//    private static final String AWS_REGION = "us-east-1"; // Change to your region
    
    @Bean
    public AmazonTextract amazonTextractClient() {
        
        return AmazonTextractClientBuilder.standard()
               
                .withRegion("us-east-1")
                .build();
    }
}