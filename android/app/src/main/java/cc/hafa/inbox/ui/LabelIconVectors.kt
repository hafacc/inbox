package cc.hafa.inbox.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

/** Each label icon id in `shared/themes/label-icons.json` drawn as its Material Icons Outlined icon. */
val LABEL_ICON_VECTORS: Map<String, ImageVector> = mapOf(
    "tag" to Icons.Outlined.LocalOffer,
    "users" to Icons.Outlined.Group,
    "bell" to Icons.Outlined.Notifications,
    "message-square" to Icons.Outlined.Forum,
    "info" to Icons.Outlined.Info,
    "inbox" to Icons.Outlined.Inbox,
    "star" to Icons.Outlined.StarOutline,
    "heart" to Icons.Outlined.FavoriteBorder,
    "briefcase" to Icons.Outlined.WorkOutline,
    "home" to Icons.Outlined.Home,
    "receipt" to Icons.Outlined.Receipt,
    "shopping-bag" to Icons.Outlined.ShoppingBag,
    "credit-card" to Icons.Outlined.CreditCard,
    "landmark" to Icons.Outlined.AccountBalance,
    "plane" to Icons.Outlined.Flight,
    "car" to Icons.Outlined.DirectionsCar,
    "truck" to Icons.Outlined.LocalShipping,
    "package" to Icons.Outlined.Inventory2,
    "calendar" to Icons.Outlined.CalendarToday,
    "book" to Icons.AutoMirrored.Outlined.MenuBook,
    "graduation-cap" to Icons.Outlined.School,
    "code" to Icons.Outlined.Code,
    "newspaper" to Icons.Outlined.Newspaper,
    "file-text" to Icons.Outlined.Description,
    "folder" to Icons.Outlined.FolderOpen,
    "music" to Icons.Outlined.MusicNote,
    "camera" to Icons.Outlined.PhotoCamera,
    "gift" to Icons.Outlined.CardGiftcard,
    "utensils" to Icons.Outlined.Restaurant,
    "coffee" to Icons.Outlined.LocalCafe,
    "dumbbell" to Icons.Outlined.FitnessCenter,
    "stethoscope" to Icons.Outlined.MedicalServices,
    "gamepad" to Icons.Outlined.SportsEsports,
    "baby" to Icons.Outlined.ChildCare,
    "paw" to Icons.Outlined.Pets,
    "leaf" to Icons.Outlined.Eco,
    "globe" to Icons.Outlined.Public,
    "lock" to Icons.Outlined.Lock,
    "flag" to Icons.Outlined.Flag,
    "sparkles" to Icons.Outlined.AutoAwesome,
)
