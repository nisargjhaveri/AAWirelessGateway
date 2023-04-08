# AAWirelessGateway

Enjoy Wireless Android Auto in your car, even if it has only wired Android Auto support.
Use a spare device as gateway/proxy to enable Wireless Android Auto from your regular phone.

The app needs to be installed in the *gateway* (device connected to the car with USB). It can connect to the the *wireless client* (the phone which will connect to the car wirelessly) using the native Android Auto connection method and does not need any apps or modification on the *wireless client*.

In case the native Android Auto connection does not work, the app can be installed in both the *gateway* and the *wireless client*. Enable the relevant mode (wireless client or gateway) from the app settings and configure other options accordingly.

Inspired by the work done by Emil (@borconi, https://github.com/borconi/AAGateWay) and others. Thanks!

## How it works

### Native Android Auto connection (recommended)
- The app in gateway mode automatically starts when an Android Auto enabled headunit is connected via USB.
- Enabled the Wifi hotspot.
- Connects to the wireless client via Bluetooth and replicates the communication Wireless Android Auto expects, including instructions and credentials to connect to the Wifi and start Wireless Android Auto.
- Android Auto on the wireless client connects seamlessly.

### Custom connection
In gateway mode:
- Automatically starts when an Android Auto enabled headunit is connected via USB.
- Enables the Wifi hotspot.
- Tries to signal the selected wireless client via Bluetooth and waits for it to connect back.
- Once connected, it starts forwarding communication between USB and the wireless client.

In wireless client mode:
- Automatically starts when the selected gateway tries to connect via Bluetooth.
- Connects to the gateway Wifi hotspot and start Wireless Android Auto pointing it to the gateway.
- If everything works, the Android Auto should start on the car display wirelessly.

## Features
- Supports native Android Auto connection, no need to install the app on wireless client.
- Also provides an option for custom connection with more options
- Easily configure the gateway and wireless client with in-app configuration options.
- Refuse wireless connection in case of low battery or power saving mode in the wireless client device.
- An option to fallback to the local Android Auto from the gateway device in case wireless connection fails (root required in gateway device for this).
- Root is not required, but can provide enhanced features when present in gateway device.
- Both gateway and wireless clients only wake up when there is a connection possible. The app doesn't run in background always.

## How to use
Install the app in both the devices. You can build the app from the source code or use of the prebuilt apks.

### Native Android Auto connection
On gateway device:
- Enable "Use this device as gateway" option.
- Ensure all the permissions are granted. Scroll to the bottom of the config page to check status.
- In "Gateway device settings" section, select "Client Bluetooth Device" as the device you want to use as the wireless client.
  - You may need to pair the device using bluetooth if not already available. You can pair by navigating to "Pair Bluetooth Device" first.
- Setup Hotspot name and password as you wish from Android Settings, make a note of it.
- Make sure "Native AA Connection" option is enabled.
- Enter "Hotspot SSID", "Hotspot password" and "Hotspot BSSID" to match the system configuration.
- (Optional) If your gateway device is rooted, make this app an system app. This enables more options such as USB Android Auto fallback.
- On the first USB connection after this, it'll ask to select which app to handle the USB accessory. Select this app and select "Always".
- Make sure your Bluetooth and Wifi are enabled in the device.
- Connect the gateway device to the car with USB.

### Custom connection
On gateway device:
- Enable "Use this device as gateway" option.
- Ensure all the permissions are granted. Scroll to the bottom of the config page to check status.
- In "Gateway device settings" section, select "Client Bluetooth Device" as the device you want to use as the wireless client.
  - You may need to pair the device using bluetooth if not already available. You can pair by navigating to "Pair Bluetooth Device" first.
- Setup Hotspot name and password as you wish from Android Settings, make a note of it.
- Disable "Native AA Connection" option.
- (Optional) If your gateway device is rooted, make this app an system app. This enables more options such as USB Android Auto fallback.
- On the first USB connection after this, it'll ask to select which app to handle the USB accessory. Select this app and select "Always".
- Make sure your Bluetooth and Wifi are enabled in the device.

For wireless client:
- Enable "Use this device as wireless client" option.
- Enter the gateway Hotspot details in "Gateway Wifi SSID" and "Gateway Wifi password".
- Enable "Use Gateway Wifi for Internet" or enter "Gateway Wifi BSSID".
- Select the gateway device in "Gateway Bluetooth Device". Make sure the device is already paired.
- On the first connection there might be a notification or dialog for allowing Wifi connection. Make sure you allow that.
- Make sure your Bluetooth and Wifi are enabled in the device.

This should make it work. Try connecting the gateway device to car with USB.

---

Please note that this hasn't been heavily tested in different cars and devices and won't necessarily work in all scenarios.
