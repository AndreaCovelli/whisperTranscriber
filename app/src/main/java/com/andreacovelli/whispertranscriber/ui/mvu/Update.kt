package com.andreacovelli.whispertranscriber.ui.mvu

data class Update<Model, Effect>(val model: Model, val effects: List<Effect> = emptyList())
