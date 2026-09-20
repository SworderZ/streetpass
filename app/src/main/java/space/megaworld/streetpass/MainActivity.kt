package space.megaworld.streetpass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import space.megaworld.streetpass.ui.AppRoot
import space.megaworld.streetpass.ui.theme.StreetPassTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StreetPassTheme {
                AppRoot()
            }
        }
    }
}
