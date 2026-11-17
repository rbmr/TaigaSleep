# Exercising the Heart Rate Service driver without a chest strap

The generic driver speaks the Bluetooth Heart Rate Service: service `0x180D`, the
Heart Rate Measurement characteristic `0x2A37`, and the battery level `0x2A19` under
`0x180F`. Its decoder is covered by the fixture in
[`shared/codec-fixtures/heart-rate-measurement.json`](../shared/codec-fixtures/heart-rate-measurement.json),
which the app and the worker tests both read, so every flag combination is checked
without a radio.

To exercise the whole path, a virtual peripheral can advertise that service.

## Linux host with a real phone

1. Install BlueZ and Python, then `pip install bless`.
2. Run a small peripheral:

   ```python
   import asyncio
   from bless import BlessServer, BlessGATTCharacteristic, GATTCharacteristicProperties, GATTAttributePermissions

   SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
   MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

   async def main():
       server = BlessServer(name="Virtual HRS")
       await server.add_new_service(SERVICE)
       await server.add_new_characteristic(
           SERVICE, MEASUREMENT,
           GATTCharacteristicProperties.notify,
           None,
           GATTAttributePermissions.readable,
       )
       await server.start()
       characteristic = server.get_characteristic(MEASUREMENT)
       # flags 0x16: contact supported, contact, RR present; 60 bpm; one 1 s interval
       while True:
           characteristic.value = bytes([0x16, 60, 0x00, 0x02])
           server.update_value(SERVICE, MEASUREMENT)
           await asyncio.sleep(1)

   asyncio.run(main())
   ```

3. On the phone, open Taiga Sleep, scan, pick the virtual strap and start a recording
   with heart rate and R-R on. The recording's `meta.json` should list both streams
   under the codec `ble-hrs-v1`, and its `hr.log` should grow by one frame a second.
4. Stop the recording and check `events.jsonl` for `connect` and `stream` lines.

Bluetooth on an Android emulator does not reliably reach the host adapter, so this
step uses a phone. The emulator is enough for the version without a radio: the fake
tracker in debug builds and the shared decoder fixture.

## What the flags mean

`0x01` selects the 16-bit heart rate, `0x02` reports contact, `0x04` says the device
supports contact, `0x08` adds energy expended and `0x10` appends R-R intervals in units
of 1/1024 s. The fixture covers all 32 combinations, and both decoders round an interval
to the nearest millisecond.
