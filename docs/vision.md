# Project vision

Sleep matters. This project aims to make it easy, affordable and secure for everyone to
get high quality insight into their sleep.

Two frustrations led here.

## Garbage in, garbage out

Most consumer wearables that offer sleep staging or other statistics about your health
are only loosely based on science, and they are generally not very accurate. The main
reason is not the algorithm. It is the input. The data they start with is simply not
enough to support the conclusions they want to draw, and they draw them anyway.

This project starts from the other end. It lets people record raw data about their own
bodies with their own devices, as accurately as those devices allow, and it refuses to
pretend the data says more than it does.

The first supported tracker is the Polar H10. More are expected to follow, such as the
Polar Verity Sense and the 2025 screenless Polar Loop.

## Subscriptions and statistics nobody can inspect

The second frustration is subscriptions and the "magic" statistics that come with them.
Both follow from the same cause: the logic is proprietary. You end up paying to use a
device you already own, and you are shown numbers with no way of knowing where they came
from.

Keeping the project completely open source solves both problems at once:

- **You own the software.** There is no subscription, and no feature held back behind one.
- **You can check the logic.** Every statistic can be traced to the code that produced it.
- **You own the data.** You host the backend yourself, download the app and connect the
  two. No company stores your health data.

Self-hosting is the default. Officially backed hosted backends may exist later, but the
project does not depend on them. To keep self-hosting simple and cheap, the project
provides containers ready to deploy, guides for setting everything up, and a deliberately
lightweight backend built on PocketBase and Go. A backend is not expected to serve
hundreds of users at the same time.

## Trackers and data sources

The first axis of modularity is where data comes from.

Some data has to be entered by the user: weight, sex, age and similar. Other data is
measured directly by a sensor. Sensors can be anything, and the data they provide differs
with the device.

The app aims to collect all of it, or all of it that the user believes is necessary.
Recording fewer streams is a reasonable choice, for example to save battery on the
device. What is recorded is stored securely on the user's phone.

The project aims for minimal standardisation and preprocessing on the phone, such that as much
of the original data as possible survives. Data is only processed during analysis on the backend.

## Preventing shutdown

The second axis of modularity exists to stop recordings from being lost.

Depending on the tracker, the phone has to stay awake for long periods, and phones are
aggressive about stopping apps that do. 
Depending on the OS of the device the app provides a flow that helps the user configure the phone accordingly. In some cases the
tracker does not need the phone to stay on, such as when it records the data on the device itself and allow the user to download it in the morning. In this case the user should not be forced to set settings in any way. 

## Recordings

All data is grouped into recordings. A recording contains its raw data streams and its
metadata.

## Where analysis happens

Analysing sleep on the phone runs into two problems:

1. Raw recordings are large. For example, high-frequency ECG can fill a phone's storage quickly.
2. Most sleep analysis algorithms are not written for phones. Porting them is likely to
   introduce bugs, and analysing a long night of data drains the battery.

So the backend exists, with two responsibilities:

1. Store the user's raw data, so that it is still there when they need it and for when a new
   algorithm can say more about an old recording than the current one could.
2. Run the analysis, store the results and serve them to the user.

This keeps the separation of concerns clear. The app is only used to record data, to talk
to the backend and to display the resulting statistics in a form that is easy to read.
Once the backend is set up, the user should never need anything but the app.

## Analysis

### When analysis runs

A recording arriving at the backend does not start anything. By default the backend
stores it and does nothing else. Further analysis is a separate request, made either by
the user or by the app on the user's behalf.

Analysis is not linear. Steps depend on earlier steps, some steps can be swapped for
another implementation, and which steps are possible at all depends on the data that is
actually available.

From the user's point of view the results fall into three categories, in no particular
order: data quality, heart rate and sleep.

### Data quality

Data quality determines whether a recording is usable and which parts of it are. This is
the garbage-in-garbage-out argument applied to our own data.

The results derived here matter in two ways. It decides what the following steps may use, and it gives the
user something to act on. A bad night should not end at "the data is bad, no analysis
today". Common failures have concrete causes, such as dry H10 electrodes or an old strap,
and the goal is to explain how to record the data correctly next time.

Where the observed artifacts allow it, the guidance should be specific rather than
generic. For example, a worn-out strap and a strap that was not on the body at all should not produce
the same message if the data can tell them apart.

### Heart rate

Heart-rate statistics are generally the easiest to derive. After cutting out unusable periods, they are the basic statistics: minimum, maximum and average heart rate, heart rate across the night,
HRV, and so on.

These statistics are close to what the tracker itself aims to record and are not some derivative (like the sleep stages that follow), and are thus expected to be the "most trustworthy" statistics provided. 

Depending on the tracker, the raw data has to be converted or modified first, albeit minimally.

### One device at a time, one shared library

All three categories (data quality, heart rate, and sleep) depend on the device that recorded the data and on the streams the
user chose to record. They have to be matched to both.

The recommended approach is to reason about each device individually: work out how to derive the
highest quality statistics that device can support, and compute those directly. The logic
worth keeping is then extracted into a library shared across devices, so that something
like RR series to HRV conversion or the detection of invalid ECG is only written once.

The aim is to never lower the quality of a statistic in order to reuse some logic that
already exists. For example, if a device provides ECG and an ECG-based sleep stager gives better
results than an HR-based one, the sleep stages of that recording should be determined with the better algorithm, instead of using the HR-based algorithm, just because it already exists.

Of course, this is an aim, and not something that is expected for every device immediately the moment it becomes available to record with in the app.

### Sleep stages

Sleep staging is the hard part. Many algorithms exist, each taking different inputs: ECG
directly or only heart rate, with or without personal data such as weight, age and sex.
They also produce different outputs, e.g. awake/asleep, awake/NREM/REM, or
awake/light/deep/REM.

Again, we aim to derive the highest quality data as possible, but which sleep stage algorithm to use may be a metter of user preference. Consequently, we provide sensible defaults (which algorithms we believe provide the highest quality predictions), but ultimately allow the user to choose the sleep staging algorithm they want to use. In addition, we may consider allowing the user to applying multiple different algorithms for the same night “to get a second opinion”.

Sleep staging algorithms should be moduar aswell. An algorithm's
interface is roughly the inputs it expects and the outputs it is capable of producing.

The app has to present every one of those output shapes well.

## Design of the app

### Control against simplicity

Almost every design decision trades user control and information against a simpler
experience. Which way to go in a given case is a judgement for the human developers, although it helps to consider multiple options in every such case.

### The app is for users, not for the developer

Configuration or information that is too advanced or irrelevant for the general user
never belongs in the app. Exact insight into the app's internal state is obtained with
Android USB debugging, not by putting it on the phone's screen.

### Show what is available, hide what is not

Next, because many devices and algorithms are expected to exist at once, we will not show all configuration and information from all devices at once.

For example, if a device or algorithm doesnt support something, we shall not write “not supported, use X instead” for every item. Instead, the element is hidden, only showing it when the data or configuration is actually available. This aims to prevent screen clutter significantly.

The risk is that the user wonders why something they expect is absent. The answer is to
make the information findable in an intuitive place, not to fill the screen with caveats.
For example, when a sleep stager reports only awake, REM and NREM, the app does not announce that light
and deep are unavailable. It names the algorithm somewhere unobtrusive, and tapping that
name explains what the algorithm needs and what it can determine aswell as maybe some general information about the algorithm such as a reference to the source code or its author.

## Backend and worker

The backend manages data and state. Users talk to the backend and never to the worker.

- **Backend:** PocketBase and Go, kept lightweight and efficient.
- **Worker:** Python, because most data analysis libraries and the algorithms are written
  in Python.

They are separate so that analysis cannot interfere with the reachability or the halting
of the API, and so that each of them stays easy to reason about.

## How to read this document

This is the reference for what the project is for and which tradeoffs it accepts written by the author directly. It is 
not a description of the current implementation.

The document is a living one, expected to grow and to be corrected as we make decisions about the direction of the project.

This file is not expected to be edited regularly, and should not be edited by any agent that is not the true author.
