#include <Arduino.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <Firebase_ESP_Client.h>
#include <Servo.h>
#include <Wire.h>
#include <RTClib.h>
#include <Adafruit_BusIO_Register.h>
#include "time.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include "esp_camera.h"
#include <LittleFS.h>
#include <FS.h>
#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"
#include "HX711.h"

// CAMERA_MODEL_AI_THINKER
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22

#define servoPin 13 // brown
#define SDA 14      // orange
#define SDL 15      // red
#define DT 2        // yellow | HX711 Amplifier for load cell
#define SCK 4       // green | HX711 Amplifier for load cell
#define cameraFlash 12

#define ssid "Bryyy's Phone"
#define password "dikoalamwalangspace"
#define API_KEY "AIzaSyBjQGjgmOahAlb15wu56Y5TVvvxvRa84nA"
#define DATABASE_URL "https://thesis-v1-pet-feeder-default-rtdb.asia-southeast1.firebasedatabase.app/"

#define STORAGE_BUCKET_ID "thesis-v1-pet-feeder.appspot.com"
#define FILE_PHOTO_PATH "/photo.jpg"
#define BUCKET_PHOTO "/data/photo.jpg"

#define ntpServer "asia.pool.ntp.org"
#define gmtOffset_sec 28800
#define daylightOffset_sec 0

const char *adjustTime = "06:00:00"; // Time kung kailan magsysync ulit si ESP sa internet time
unsigned long startTime = 0;
const unsigned long rotationTime = 1000;

int pos;
int calibrationValue = 533;
int counter = 0;
int timeout = 120;

FirebaseData fbdo, fbdo_s1, fbdo_s2, fbdo_s3, fbdo_s4, fbdo_s5;
FirebaseAuth auth;
FirebaseConfig config;
RTC_DS3231 rtc;
Servo rotary;
HX711 scale;

boolean ntpAdjusted = false;
boolean signupOK = false;
boolean streamFlag = false;

char newTime[9], newDate[15], setTime1[9], setTime2[9], setTime3[9];
float scaleOffset = 6.0;
float scaleSet, petWeight;

void capturePhotoSaveLittleFS(void);
void setupCamera();
void initAll();
void connectToWiFi();
void initFirebase();
void setLocalTime();
void initLittleFS();
void fcsUploadCallback(FCS_UploadStatusInfo info);

void downloadTime(void *parameter);
void timeTick(void *parameter);
void servoTask(void *parameter);

TaskHandle_t downloadTime_handler;
TaskHandle_t timeTick_handler;
TaskHandle_t servo_handler;

void setup()
{
    Serial.begin(115200);

    Serial.print("Setup: Executing on core ");
    Serial.println(xPortGetCoreID());

    initAll(); // Initialize Servo Pin, RTC module, ESP CAM.

    initLittleFS();

    setupCamera();

    connectToWiFi(); // Persistent Connection to WiFi

    setLocalTime(); // Set the time grabbed from the NTP server to the RTC

    initFirebase();

    xTaskCreatePinnedToCore(
        timeTick,
        "Time Tick",
        10240,
        NULL,
        3,
        &timeTick_handler,
        1);

    // xTaskCreatePinnedToCore(
    // uploadFrame,
    // "Upload Frame",
    // 10240,
    // NULL,
    // 1,
    // &uploadFrame_handler,
    // 1);

    xTaskCreatePinnedToCore(
        downloadTime,
        "Download Time",
        10240,
        NULL,
        2,
        &downloadTime_handler,
        1);

    xTaskCreatePinnedToCore(
        servoTask,
        "Servo Task",
        10240,
        NULL,
        2,
        &servo_handler,
        1);
}

void loop()
{
    vTaskDelete(NULL);
}

void timeTick(void *parameters)
{
    while (1)
    {
        // Serial.print("\nCreated task timeTick: Executing on core ");
        // Serial.println(xPortGetCoreID());

        DateTime now = rtc.now(); // Create a DateTime object containing the current time value of the RTC

        sprintf(newDate, "%04d/%02d/%02d", now.year(), now.month(), now.day());     // Set new_date to a string value of "YYYY/MM/DD"
        sprintf(newTime, "%02d:%02d:%02d", now.hour(), now.minute(), now.second()); // Set new_time to a string value of "HH:MM:SS"

        if (strstr(newTime, adjustTime) != NULL) // If value of new_time is equal to value of adjust_time, try to sync NTP servers again. || RESYNC TIME EVERYDAY
        {
            if (!ntpAdjusted)
            {
                setLocalTime();
                ntpAdjusted = true;
            }
            else
            {
                ntpAdjusted = false;
            }
        }

        vTaskDelay(1000 / portTICK_PERIOD_MS);
    }
}

void downloadTime(void *parameter)
{

    while (1)
    {
        if (Firebase.ready() && signupOK)
        {
            // Serial.print("\nCreated task uploadFrame: Executing on core ");
            // Serial.println(xPortGetCoreID());
            if (streamFlag)
            {
                streamFlag = false;

                digitalWrite(cameraFlash, HIGH);

                capturePhotoSaveLittleFS();

                vTaskDelay(50 / portTICK_PERIOD_MS);

                // Serial.print("\nCreated task uploadFrame: Executing on core ");
                // Serial.println(xPortGetCoreID());
                Serial.println("Uploading picture... ");
                // MIME type should be valid to avoid the download problem.
                // The file systems for flash and SD/SDMMC can be changed in FirebaseFS.h.

                if (Firebase.Storage.upload(&fbdo, STORAGE_BUCKET_ID, FILE_PHOTO_PATH, mem_storage_type_flash, BUCKET_PHOTO, "image/jpeg", fcsUploadCallback))
                {
                    Serial.printf("\nDownload URL: %s\n", fbdo.downloadURL().c_str());

                    vTaskDelay(50 / portTICK_PERIOD_MS);

                    counter++;

                    if (Firebase.RTDB.setInt(&fbdo, "/Camera/counter", counter))
                    {
                        Serial.println("Success in incrementing counter.");
                    }
                    else
                    {
                        Serial.println("FAILED: " + fbdo.errorReason());
                    }

                    digitalWrite(cameraFlash, LOW);
                }
                else
                {
                    Serial.println(fbdo.errorReason());
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);

                if (Firebase.RTDB.setBool(&fbdo, "/Camera/streamFlag", false))
                {
                    Serial.println("Success in turning stream flag off.");
                }
            }
            else
            {
                if (!Firebase.RTDB.readStream(&fbdo_s1))
                {
                    Serial.println("stream 1 read error, " + fbdo_s1.errorReason());
                }

                if (fbdo_s1.streamAvailable())
                {

                    strcpy(setTime1, fbdo_s1.stringData().c_str());

                    Serial.println("Successfully read from " + fbdo_s1.dataPath() + ": " + setTime1);
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);

                if (!Firebase.RTDB.readStream(&fbdo_s2))
                {
                    Serial.println("stream 2 read error, " + fbdo_s2.errorReason());
                }
                if (fbdo_s2.streamAvailable())
                {
                    strcpy(setTime2, fbdo_s2.stringData().c_str());
                    Serial.println("Successfully read from " + fbdo_s2.dataPath() + ": " + setTime2);
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);

                if (!Firebase.RTDB.readStream(&fbdo_s3))
                {
                    Serial.println("stream 3 read error, " + fbdo_s3.errorReason());
                }
                if (fbdo_s3.streamAvailable())
                {
                    strcpy(setTime3, fbdo_s3.stringData().c_str());
                    Serial.println("Successfully read from " + fbdo_s3.dataPath() + ": " + setTime3);
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);

                if (!Firebase.RTDB.readStream(&fbdo_s4))
                {
                    Serial.println("stream 4 read error, " + fbdo_s4.errorReason());
                }
                if (fbdo_s4.streamAvailable())
                {
                    streamFlag = fbdo_s4.boolData();
                    Serial.println("Successfully read from " + fbdo_s4.dataPath() + ": " + streamFlag);
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);

                if (!Firebase.RTDB.readStream(&fbdo_s5))
                {
                    Serial.println("stream 5 read error, " + fbdo_s5.errorReason());
                }
                if (fbdo_s5.streamAvailable())
                {
                    petWeight = (float)fbdo_s5.intData();
                    Serial.println("Successfully read from " + fbdo_s5.dataPath() + ": " + petWeight);
                }

                vTaskDelay(50 / portTICK_PERIOD_MS);
            }

            vTaskDelay(500 / portTICK_PERIOD_MS);
        }
    }
}

void servoTask(void *parameter)
{
    while (1)
    {
        Serial.println(newTime);
        Serial.println(setTime1);
        Serial.println(setTime2);
        Serial.println(setTime3);

        scaleSet = (float)petWeight - scaleOffset;
        Serial.print("Scale Set: ");
        Serial.println(scaleSet);

        if (strstr(newTime, setTime1) != NULL || strstr(newTime, setTime2) != NULL || strstr(newTime, setTime3) != NULL)
        {
            Serial.println("Servo turns");
            scale.power_up();
            float currentWeight = scale.get_units();
            Serial.print("Current Weight: ");
            Serial.println(currentWeight);

            while (currentWeight <= scaleSet)
            {
                currentWeight = scale.get_units();
                unsigned long currentTime = millis();
                Serial.print("Current Weight: ");
                Serial.println(currentWeight);

                // Rotate clockwise for 1 second
                if (currentTime - startTime < rotationTime)
                {
                    rotary.writeMicroseconds(1000); // Adjust the value based on your servo specifications
                }
                // Rotate counter-clockwise for 1 second
                else if (currentTime - startTime < 2 * rotationTime)
                {
                    rotary.writeMicroseconds(2000); // Adjust the value based on your servo specifications
                }
                // Reset the timer after 2 seconds
                else
                {
                    startTime = currentTime;
                }
            }
            rotary.write(90);
        }
        else
        {
            Serial.println("Servo does not turn");
            scale.power_down();
            rotary.write(90);
        }
        vTaskDelay(1000 / portTICK_PERIOD_MS);
    }
}

void initAll()
{
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    pinMode(cameraFlash, OUTPUT);

    rotary.attach(servoPin); // set IO pin as PWM control for Servo
    rotary.write(90);

    Serial.println("Initializing Scale...");
    scale.begin(DT, SCK);
    scale.set_scale(calibrationValue);
    scale.tare();

    Wire.begin(SDA, SDL); // Wire IO pins as SDA and SDL
    if (!rtc.begin())     // If RTC does not initialize
    {
        Serial.println("Couldn't find RTC!");
        Serial.flush();
        abort();
    }
}

void initLittleFS()
{
    if (!LittleFS.begin(true))
    {
        Serial.println("An Error has occurred while mounting LittleFS");
        ESP.restart();
    }
    else
    {
        delay(500);
        Serial.println("LittleFS mounted successfully");
    }
}

void fcsUploadCallback(FCS_UploadStatusInfo info)
{
    if (info.status == firebase_fcs_upload_status_init)
    {
        Serial.printf("Uploading file %s (%d) to %s\n", info.localFileName.c_str(), info.fileSize, info.remoteFileName.c_str());
    }
    else if (info.status == firebase_fcs_upload_status_upload)
    {
        Serial.printf("Uploaded %d%s, Elapsed time %d ms\n", (int)info.progress, "%", info.elapsedTime);
    }
    else if (info.status == firebase_fcs_upload_status_complete)
    {
        Serial.println("Upload completed\n");
        FileMetaInfo meta = fbdo.metaData();
        Serial.printf("Name: %s\n", meta.name.c_str());
        Serial.printf("Bucket: %s\n", meta.bucket.c_str());
        Serial.printf("contentType: %s\n", meta.contentType.c_str());
        Serial.printf("Size: %d\n", meta.size);
        Serial.printf("Generation: %lu\n", meta.generation);
        Serial.printf("Metageneration: %lu\n", meta.metageneration);
        Serial.printf("ETag: %s\n", meta.etag.c_str());
        Serial.printf("CRC32: %s\n", meta.crc32.c_str());
        Serial.printf("Tokens: %s\n", meta.downloadTokens.c_str());
        Serial.printf("Download URL: %s\n\n", fbdo.downloadURL().c_str());
    }
    else if (info.status == firebase_fcs_upload_status_error)
    {
        Serial.printf("Upload failed, %s\n", info.errorMsg.c_str());
    }
}

void connectToWiFi()
{
    WiFiManager wm;

    // wm.resetSettings();

    bool res;

    res = wm.autoConnect("Pet Smart 101");

    if (!res)
    {
        Serial.println("Failed to connect");
    }

    long int StartTime = millis();
    while (WiFi.status() != WL_CONNECTED)
    {
        delay(500);
        Serial.print(".");
        if ((StartTime + 10000) < millis())
            break;
    }
    Serial.println("\nWiFi connected.");
}

void initFirebase()
{
    config.api_key = API_KEY;
    config.database_url = DATABASE_URL;

    // config.token_status_callback = tokenStatusCallback;

    if (Firebase.signUp(&config, &auth, "", ""))
    {
        Serial.println("Sign Up OK");
        signupOK = true;
    }
    else
    {
        Serial.printf("%s\n", config.signer.signupError.message.c_str());
    }

    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);

    if (Firebase.RTDB.getString(&fbdo, "/Clock/setTime1"))
    {
        if (fbdo.dataType() == "string")
        {
            strcpy(setTime1, fbdo.stringData().c_str());
            Serial.println("Successfully read from Time 1");
        }
    }
    else
    {
        Serial.println("FAILED: " + fbdo.errorReason());
    }

    delay(50);

    if (Firebase.RTDB.getString(&fbdo, "/Clock/setTime2"))
    {
        if (fbdo.dataType() == "string")
        {
            strcpy(setTime2, fbdo.stringData().c_str());
            Serial.println("Successfully read from Time 2");
        }
    }
    else
    {
        Serial.println("FAILED: " + fbdo.errorReason());
    }

    delay(50);

    if (Firebase.RTDB.getString(&fbdo, "/Clock/setTime3"))
    {
        if (fbdo.dataType() == "string")
        {
            strcpy(setTime3, fbdo.stringData().c_str());
            Serial.println("Successfully read from Time 3");
        }
    }

    delay(50);

    if (Firebase.RTDB.setBool(&fbdo, "/Camera/streamFlag", false))
    {
        Serial.println("Success in turning stream flag off.");
    }
    else
    {
        Serial.println("FAILED: " + fbdo.errorReason());
    }

    if (Firebase.RTDB.setInt(&fbdo, "/Camera/counter", counter))
    {
        Serial.println("Success in setting Counter to 0.");
    }

    delay(50);

    if (Firebase.RTDB.getInt(&fbdo, "/Dispenser/weight"))
    {
        if (fbdo.dataType() == "int")
        {
            petWeight = (float)fbdo.intData();
            Serial.println("Successfully read from weight");
        }
    }

    if (!Firebase.RTDB.beginStream(&fbdo_s1, "/Clock/setTime1"))
    {
        Serial.println("Stream 1 begin error: " + fbdo_s1.errorReason());
    }

    delay(50);

    if (!Firebase.RTDB.beginStream(&fbdo_s2, "/Clock/setTime2"))
    {
        Serial.println("Stream 2 begin error: " + fbdo_s2.errorReason());
    }

    delay(50);

    if (!Firebase.RTDB.beginStream(&fbdo_s3, "/Clock/setTime3"))
    {
        Serial.println("Stream 3 begin error: " + fbdo_s3.errorReason());
    }

    delay(50);

    if (!Firebase.RTDB.beginStream(&fbdo_s4, "/Camera/streamFlag"))
    {
        Serial.println("Stream 4 begin error: " + fbdo_s4.errorReason());
    }

    delay(500);

    if (!Firebase.RTDB.beginStream(&fbdo_s5, "/Dispenser/weight"))
    {
        Serial.println("Stream 5 begin error: " + fbdo_s5.errorReason());
    }

    delay(50);
}

void setLocalTime()
{
    struct tm timeinfo; // Define Structure as timeinfo
    int yearNow, monthNow, dayNow, hourNow, minuteNow, secondNow;
    configTime(gmtOffset_sec, daylightOffset_sec, ntpServer); // Grab time from NTP Server

    // If can't grab time from NTP Server
    if (!getLocalTime(&timeinfo))
    {
        DateTime now = rtc.now();                                               // Create a DateTime object containing the current time value of the RTC
        sprintf(newDate, "%04d/%02d/%02d", now.year(), now.month(), now.day()); // Set new_date to a string value of "YYYY/MM/DD"
        sprintf(newTime, "%02d:%02d:%02d", now.hour(), now.minute(), now.second());
        Serial.println("Failed to obtain time.");
        return;
    }

    // If there was time grabbed from NTP server
    yearNow = timeinfo.tm_year + 1900;
    monthNow = timeinfo.tm_mon + 1;
    dayNow = timeinfo.tm_mday;
    hourNow = timeinfo.tm_hour;
    minuteNow = timeinfo.tm_min;
    secondNow = timeinfo.tm_sec;

    rtc.adjust(DateTime(yearNow, monthNow, dayNow, hourNow, minuteNow, secondNow)); // Set the time on RTC as the value grabbed from NTP Server
    DateTime now = rtc.now();                                                       // Create a DateTime object containing the current time value of the RTC
    sprintf(newDate, "%04d/%02d/%02d", now.year(), now.month(), now.day());         // Set new_date to a string value of "YYYY/MM/DD"
    sprintf(newTime, "%02d:%02d:%02d", now.hour(), now.minute(), now.second());

    Serial.println("Time Synchronized.");
}

void setupCamera()
{
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sscb_sda = SIOD_GPIO_NUM;
    config.pin_sscb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;
    // init with high specs to pre-allocate larger buffers
    if (psramFound())
    {

        config.frame_size = FRAMESIZE_HD;

        config.jpeg_quality = 12; // 0-63 lower number means higher quality

        config.fb_count = 2;
    }
    else
    {

        config.frame_size = FRAMESIZE_SVGA;

        config.jpeg_quality = 12; // 0-63 lower number means higher quality

        config.fb_count = 1;
    }
    // camera init
    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK)
    {
        Serial.printf("Camera init failed with error 0x%x", err);
        delay(1000);
        ESP.restart();
    }
    sensor_t *s = esp_camera_sensor_get();

    if (s->id.PID == OV3660_PID)
    {
        s->set_vflip(s, 1);       // flip it back
        s->set_brightness(s, 2);  // up the brightness just a bit
        s->set_saturation(s, -2); // lower the saturation
    }

    s->set_framesize(s, FRAMESIZE_HVGA); // VGA|CIF|QVGA|HQVGA|QQVGA ( UXGA? SXGA? XGA? SVGA? )
}

void capturePhotoSaveLittleFS(void)
{
    // Dispose first pictures because of bad quality
    camera_fb_t *fb = NULL;
    // Skip first 3 frames (increase/decrease number as needed).
    for (int i = 0; i < 2; i++)
    {
        fb = esp_camera_fb_get();
        esp_camera_fb_return(fb);
        fb = NULL;
    }

    // Take a new photo
    fb = NULL;
    fb = esp_camera_fb_get();
    if (!fb)
    {
        Serial.println("Camera capture failed");
        delay(1000);
        ESP.restart();
    }

    // Photo file name
    Serial.printf("Picture file name: %s\n", FILE_PHOTO_PATH);
    File file = LittleFS.open(FILE_PHOTO_PATH, FILE_WRITE);

    // Insert the data in the photo file
    if (!file)
    {
        Serial.println("Failed to open file in writing mode");
    }
    else
    {
        file.write(fb->buf, fb->len); // payload (image), payload length
        Serial.print("The picture has been saved in ");
        Serial.print(FILE_PHOTO_PATH);
        Serial.print(" - Size: ");
        Serial.print(fb->len);
        Serial.println(" bytes");
    }
    // Close the file
    file.close();
    esp_camera_fb_return(fb);
}