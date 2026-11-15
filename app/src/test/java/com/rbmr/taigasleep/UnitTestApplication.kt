package com.rbmr.taigasleep

import android.app.Application

/**
 * The Application of a unit test: it has the app's resources and manifest but starts none of the
 * app's wiring, so a test that needs a collaborator installs it and nothing runs behind its back.
 */
class UnitTestApplication : Application()
