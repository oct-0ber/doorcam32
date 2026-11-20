# doorcam32 - esp32-s3-cam based wifi doorbell

## what is this?

as the name suggests, this is a doorbell based on the esp32-s3-cam  
(in my case the version from [goouuu](https://github.com/zhuhai-esp/ESP32-S3-Goouuu-Cam/tree/main))  
that uses wifi to communicate via a *python app for pc* or *an android mobile app*

## what components will i need?

for this project with the unedited code you will need:
* the base components:
  *  esp32-s3-cam board (unmodded code works for goouuu esp32-s3-cam board)
  *  inmp441 microphone
  *  a speaker of choice
  *  pcm5102 dac module
  *  pam8403 amp module
* and the customizable ones:
  * button - i used a ttp223 module
  * ws2812 led ring - here the project uses an 8-led ring
  * ***optional:*** an antenna for better signal outside
 
## how do i wire this?
here is the wiring diagram (again, for the unedited code):
  <img width="2400" height="1260" alt="circuit" src="https://github.com/user-attachments/assets/5304150c-ceb6-4434-9dd0-42519ca1bdd1"/>
and if i made it too unreadable (sorry if i did, im stupid asf) heres a table with the connections:
* ttp223
    * gnd - gnd
    * i/o - g47
    * vcc - 3.3v
* led ring (ws2812)
    * vcc (often labeled 5v) - 5v 
    * gnd - gnd
    * din / di - g48
    * dout / do - x 
* inmp441:
    * vdd - 3.3v
    * gnd - gnd
    * l/r - gnd (set to left channel via gnd)
    * sd - g14
    * ws - g2
    * sck - g1
* pcm5102
    * vin - 5v
    * gnd - gnd
    * lck - g2
    * din - g21
    * bck - g1
    * sck - gnd
    * xsmt - 3.3v
    * fmt - gnd
    * lout - pam8403 l-in
    * lgnd - pam8403 gnd
* pam8403
    * vin (+) - 5v
    * gnd (-) - gnd
    * pcm5102 connections
    * l-out+ - speaker positive terminal
    * l-out- - speaker negative terminal

## how was this coded?
so because i absolutely suck at coding, i just let github copilot make this project for me,  
well atleast i can understand the code it has written :3

## and how am i supposed to use this project?
the project includes both a python app located [here](client/doorbell_client.py)  
and an app for android devices over [here](https://github.com/oct-0ber/doorcam32/releases/tag/app-android)

both apps connect to the board via wifi, however while in the python file you put the token in the "token.txt" file  
while the android app asks you to type in the token into the login credentials, alongside the doorbell ip adress

### wait, what token?

yeah, these apps use a token to make sure your data is secure c:  
it has to be atleast 16 characters, id recommend making a random one using a generator

### what software should i use to flash the code?
you should use vs-code with the platform-io extension since thats what the project is based on  
arduino ide or esp-idf ide wont work here

## and how does the app look?
so here's the android app:
<img width="945" height="2048" alt="WhatsApp Image 2026-11-20 at 19 21 06" src="https://github.com/user-attachments/assets/fc4a7bc4-e56a-4c41-8731-1c1df3bce38f" />

and python one (yes ik it looks mid):
<img width="349" height="375" alt="obraz" src="https://github.com/user-attachments/assets/f69428d5-48d8-4225-b8c9-84852f1fcf6a" />

but besides that, the led ring reacts based on the status:
* on boot: a rainbow
* standby: tiny colorful sparks every few seconds
* calling: blue spinner
* connected: full green ring
* call ended: 3x white flash

