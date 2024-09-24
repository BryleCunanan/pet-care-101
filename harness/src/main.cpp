#include <Arduino.h>
#define TINY_GSM_MODEM_SIM800

#include <Wire.h>
#include "MAX30105.h"
#include "heartRate.h"
#include <AltSoftSerial.h>
#include <TinyGsmClient.h>
#include <SoftwareSerial.h>
#include <TinyGPS++.h>

//#if !defined(TINY_GSM_RX_BUFFER)
#define TINY_GSM_RX_BUFFER 64
//#endif


#define MODEM_RST            5
#define MODEM_PWKEY          4
#define MODEM_POWER_ON       23
#define MODEM_RX             8
#define MODEM_TX             9
#define GPS_RX               3 // GPS Tx connected to Arduino Rx (Digital Pin 3)
#define GPS_TX               2 // GPS Rx connected to Arduino Tx (Digital Pin 2)

AltSoftSerial SerialAT(MODEM_RX,MODEM_TX);
SoftwareSerial SerialGPS(GPS_RX, GPS_TX);

MAX30105 heart;

TinyGPSPlus gps;

const char apn[]  = "internet";
const char gprsUser[] = "";
const char gprsPass[] = "";

const char server[] = "******.000webhostapp.com"; 
const int  port = 80;

String latitude, longitude;

const byte RATE_SIZE = 5; //Increase this for more averaging. 4 is good.
byte rates[RATE_SIZE]; //Array of heart rates
byte rateSpot = 0;
long lastBeat = 0; //Time at which the last beat occurred

float beatsPerMinute;
int beatAvg;
int count = 0;

TinyGsm modem(SerialAT);
TinyGsmClient client(modem);

unsigned long lastUpdateTime = 0;
const unsigned long updateInterval = 1L * 1000L;

void setup() {
  // put your setup code here, to run once:
  Serial.begin(115200);
  SerialAT.begin(9600);
  SerialGPS.begin(9600);
  Serial.println("Initializing...");

 initHeart();
  initGSM();
}

void loop() {
  // put your main code here, to run repeatedly:
  // get heartbeat;
  // get long get lat every second;
  // POST LONG LAT;

//  getHeart();
//   getGPS();

   if(millis() - lastUpdateTime >=  updateInterval) {
      httpPostRequest();
      lastUpdateTime = millis();
   } 

}

void initHeart() {
  // Initialize sensor
  if (!heart.begin(Wire, I2C_SPEED_FAST)) //Use default I2C port, 400kHz speed
  {
    Serial.println("MAX30105 was not found. Please check wiring/power. ");
    while (1);
  }
  Serial.println("Place your index finger on the sensor with steady pressure.");

  heart.setup(); //Configure sensor with default settings
  heart.setPulseAmplitudeRed(0x0A); //Turn Red LED to low to indicate sensor is running
  heart.setPulseAmplitudeGreen(0); //Turn off Green LED
}

void initGSM() {

  Serial.println("Wait...");

  // Set GSM module baud rate
  Serial.println("Initializing modem...");
  modem.init();

  String modemInfo = modem.getModemInfo();
  Serial.print("Modem Info: ");
  Serial.println(modemInfo);
  
  Serial.print(F("Connecting to "));
   Serial.print(apn);
   if (!modem.gprsConnect(apn, gprsUser, gprsPass)) {
      Serial.println(" fail");
      delay(10000);
      return;
    }
   Serial.println(" success");

   if (modem.isGprsConnected()) {
      Serial.println("GPRS connected");
  }

  while(!modem.isGprsConnected()){
    Serial.print(F("Connecting to "));
    Serial.print(apn);
    if(!modem.gprsConnect(apn, gprsUser, gprsPass)) {
      Serial.println(" fail");
      delay(10000);
    }
  }
  Serial.println(" success");

  Serial.print("Connecting to ");
  Serial.println(server);
  if (!client.connect(server, port)) {
    Serial.println(" fail");
    delay(10000);
    return;
  }
  Serial.println(" success");
}

void getHeart() {
  long irValue;
  long delta = millis() - lastBeat;
  lastBeat = millis();

  while(count != RATE_SIZE){
    irValue = heart.getIR();

    
    if (irValue < 50000){
    Serial.print(" No finger?");
    }
    else
    {
      //We sensed a beat!
      Serial.print("Measuring... ");
      Serial.println(count);
      long delta = millis() - lastBeat;
      lastBeat = millis();

      beatsPerMinute = 60 / (delta / 1000.0);

      if (beatsPerMinute < 255 && beatsPerMinute > 20)
      {
        rates[rateSpot++] = (byte)beatsPerMinute; //Store this reading in the array
        rateSpot %= RATE_SIZE; //Wrap variable

        //Take average of readings

        count++;
      }
    }
    
  }
  beatAvg = 0;
  for (byte i = 0 ; i < RATE_SIZE ; i++)
  beatAvg += rates[i];
  beatAvg /= RATE_SIZE;

  Serial.print("Avg BPM=");
  Serial.print(beatAvg);
  count = 0;

  Serial.println();
}

void getGPS() {
   boolean newData = false;
    for (unsigned long start = millis(); millis() - start < 2000;){
      while (SerialGPS.available() > 0){
        if (gps.encode(SerialGPS.read())){
          newData = true;
          break;
        }
      }
      
  } 
 
  if(newData){
  newData = false;
  
  latitude = String(gps.location.lat(), 6); // Latitude in degrees (double)
  longitude = String(gps.location.lng(), 6); // Longitude in degrees (double)

  
  Serial.print("Latitude= "); 
  Serial.println(latitude);
  Serial.print("Longitude= "); 
  Serial.println(longitude);
  }
}

void httpPostRequest() {
  longitude = 100;
  latitude =100;
  beatAvg = 100;
  
  String resource = "longitude=" + String(longitude) + "&latitude=" + String(latitude) + "&heart=" + String(beatAvg);

  
  while(!client.connected()){
    client.connect(server, port); 
  }

if(client.connected()){
  Serial.println("Performing HTTP POST request...");
  Serial.println("Connected");
    client.println("POST /upload.php HTTP/1.1");
    client.println("Host: " + String(server));
    client.println("Content-Type: application/x-www-form-urlencoded");
    client.print("Content-Length: ");
    client.println(resource.length());
    client.println("Connection: close");
    client.println();
    client.println(resource);
}

 if (client.connect(server, port)) {
    Serial.println("Connected");
    client.println("POST /upload.php HTTP/1.1");
    client.println("Host: " + String(server));
    client.println("Content-Type: application/x-www-form-urlencoded");
    client.print("Content-Length: ");
    client.println(resource.length());
    client.println("Connection: close");
    client.println();
    client.println(resource);

}

  uint32_t timeout = millis();
  
  while (client.connected() && millis() - timeout < 10000L) {
     // Print available data
     while (client.available()) {
         char c = client.read();
         Serial.print(c);
         timeout = millis();
     }
    }

  if(!client.connected()) {
    Serial.println("Server disconnected");
  } 
  else if (millis() - timeout >= 10000L) {
    Serial.println("Timeout occurred");
  }
}